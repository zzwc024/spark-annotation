/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.client

import java.util.concurrent._
import java.util.concurrent.{Future => JFuture, ScheduledFuture => JScheduledFuture}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

import org.apache.spark.SparkConf
import org.apache.spark.deploy.{ApplicationDescription, ExecutorState}
import org.apache.spark.deploy.DeployMessages._
import org.apache.spark.deploy.master.Master
import org.apache.spark.internal.Logging
import org.apache.spark.rpc._
import org.apache.spark.util.{RpcUtils, ThreadUtils}

/**
 * 允许应用程序与Spark独立集群管理器通信的接口。
  * Interface allowing applications to speak with a Spark standalone cluster manager.
 *
 * Takes a master URL, an app description, and a listener for cluster events, and calls
 * back the listener when various events occur.
 *
 * @param masterUrls Each url should look like spark://host:port.
 */
private[spark] class StandaloneAppClient(
    rpcEnv: RpcEnv,
    masterUrls: Array[String],
    appDescription: ApplicationDescription, // app描述信息
    listener: StandaloneAppClientListener,  // 对集群事件的监听器
    conf: SparkConf)
  extends Logging {
  /** Master的RPC地址*/
  private val masterRpcAddresses = masterUrls.map(RpcAddress.fromSparkURL(_))
  /** 注册超时时间*/
  private val REGISTRATION_TIMEOUT_SECONDS = 20
  /** 注册重试次数*/
  private val REGISTRATION_RETRIES = 3
  /** 持有ClientEndPoint的RpcEndpoint*/
  private val endpoint = new AtomicReference[RpcEndpointRef]
  private val appId = new AtomicReference[String]
  /** 是否已经将app注册到master*/
  private val registered = new AtomicBoolean(false)
  // app与集群对话的客户端
  private class ClientEndpoint(override val rpcEnv: RpcEnv) extends ThreadSafeRpcEndpoint
    with Logging {
    /** 处于激活状态的Master的RpcEndpointRef*/
    private var master: Option[RpcEndpointRef] = None
    // To avoid calling listener.disconnected() multiple times
    /** 是否已经与master断开连接*/
    private var alreadyDisconnected = false
    // To avoid calling listener.dead() multiple times
    /** 是否已经死掉,防止多次调用dead方法*/
    private val alreadyDead = new AtomicBoolean(false)
    /** 保存向master注册app的任务返回的future*/
    private val registerMasterFutures = new AtomicReference[Array[JFuture[_]]]
    /** 提交关于注册的定时调度返回的JScheduledFuture*/
    private val registrationRetryTimer = new AtomicReference[JScheduledFuture[_]]

    // A thread pool for registering with masters. Because registering with a master is a blocking
    // action, this thread pool must be able to create "masterRpcAddresses.size" threads at the same
    // time so that we can register with all masters.
    private val registerMasterThreadPool = ThreadUtils.newDaemonCachedThreadPool(
      "appclient-register-master-threadpool",
      masterRpcAddresses.length // Make sure we can register with all masters at the same time
    )

    // A scheduled executor for scheduling the registration actions
    private val registrationRetryThread =
      ThreadUtils.newDaemonSingleThreadScheduledExecutor("appclient-registration-retry-thread")
    /** 注册rpcenv的dispatcher时会触发该方法*/
    override def onStart(): Unit = {
      try {
          // 向master注册
        registerWithMaster(1)
      } catch {
        case e: Exception =>
          logWarning("Failed to connect to master", e)
          // 如果异常,标记为断开连接
          markDisconnected()
          // 停止client
          stop()
      }
    }

    /**
     * 尝试向所有master注册
      *  Register with all masters asynchronously and returns an array `Future`s for cancellation.
     */
    private def tryRegisterAllMasters(): Array[JFuture[_]] = {
      for (masterAddress <- masterRpcAddresses) yield {
        // 注册线程池中提交任务
        registerMasterThreadPool.submit(new Runnable {
          override def run(): Unit = try {
            // 如果注册了直接返回
            if (registered.get) {
              return
            }
            logInfo("Connecting to master " + masterAddress.toSparkURL + "...")
            // 设置master的endpointref
            val masterRef = rpcEnv.setupEndpointRef(masterAddress, Master.ENDPOINT_NAME)
            // 向master发送RegisterApplication消息
            masterRef.send(RegisterApplication(appDescription, self))
          } catch {
            case ie: InterruptedException => // Cancelled
            case NonFatal(e) => logWarning(s"Failed to connect to master $masterAddress", e)
          }
        })
      }
    }

    /**
     * 异步向所有master注册
      * Register with all masters asynchronously. It will call `registerWithMaster` every
     * REGISTRATION_TIMEOUT_SECONDS seconds until exceeding REGISTRATION_RETRIES times.
     * Once we connect to a master successfully, all scheduling work and Futures will be cancelled.
     *
     * nthRetry means this is the nth attempt to register with master.
     */
    private def registerWithMaster(nthRetry: Int) {
      // 向所有master尝试注册app,并将返回的Future保存到registerMasterFutures.
      registerMasterFutures.set(tryRegisterAllMasters())
      // 向registrationRetryThread提交定时任务.
      registrationRetryTimer.set(registrationRetryThread.schedule(new Runnable {
        override def run(): Unit = {
          // 如果注册成功
          if (registered.get) {
            // 向其他master取消注册
            registerMasterFutures.get.foreach(_.cancel(true))
            // 关闭ixanchengchi
            registerMasterThreadPool.shutdownNow()
          } else if (nthRetry >= REGISTRATION_RETRIES) {
            // 重试超过次数,标记当前clientEndpoint进入dead状态
            markDead("All masters are unresponsive! Giving up.")
          } else {
            // 其他情况
            // 取消注册app
            registerMasterFutures.get.foreach(_.cancel(true))
            // 添加重试次数
            registerWithMaster(nthRetry + 1)
          }
        }
      }, REGISTRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    /**
     * Send a message to the current master. If we have not yet registered successfully with any
     * master, the message will be dropped.
     */
    private def sendToMaster(message: Any): Unit = {
      master match {
        case Some(masterRef) => masterRef.send(message)
        case None => logWarning(s"Drop $message because has not yet connected to master")
      }
    }

    private def isPossibleMaster(remoteAddress: RpcAddress): Boolean = {
      masterRpcAddresses.contains(remoteAddress)
    }
    // 处理消息
    override def receive: PartialFunction[Any, Unit] = {
      case RegisteredApplication(appId_, masterRef) =>
        // FIXME How to handle the following cases?
        // 1. A master receives multiple registrations and sends back multiple
        // RegisteredApplications due to an unstable network.
        // 2. Receive multiple RegisteredApplication from different masters because the master is
        // changing.
        appId.set(appId_)
        registered.set(true)
        master = Some(masterRef)
        listener.connected(appId.get)

      case ApplicationRemoved(message) =>
        markDead("Master removed our application: %s".format(message))
        stop()

      case ExecutorAdded(id: Int, workerId: String, hostPort: String, cores: Int, memory: Int) =>
        val fullId = appId + "/" + id
        logInfo("Executor added: %s on %s (%s) with %d core(s)".format(fullId, workerId, hostPort,
          cores))
        listener.executorAdded(fullId, workerId, hostPort, cores, memory)

      case ExecutorUpdated(id, state, message, exitStatus, workerLost) =>
        val fullId = appId + "/" + id
        val messageText = message.map(s => " (" + s + ")").getOrElse("")
        logInfo("Executor updated: %s is now %s%s".format(fullId, state, messageText))
        if (ExecutorState.isFinished(state)) {
          listener.executorRemoved(fullId, message.getOrElse(""), exitStatus, workerLost)
        }

      case WorkerRemoved(id, host, message) =>
        logInfo("Master removed worker %s: %s".format(id, message))
        listener.workerRemoved(id, host, message)

      case MasterChanged(masterRef, masterWebUiUrl) =>
        logInfo("Master has changed, new master is at " + masterRef.address.toSparkURL)
        master = Some(masterRef)
        alreadyDisconnected = false
        masterRef.send(MasterChangeAcknowledged(appId.get))
    }
    // 从写的通信的receiveAndReply方法
    override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
      // 收到stopAppClient后,向master发送UnregisterApplication消息
      case StopAppClient =>
        markDead("Application has been stopped.")
        sendToMaster(UnregisterApplication(appId.get))
        context.reply(true)
        stop()
      // 转发给Master
      case r: RequestExecutors =>
        master match {
          case Some(m) => askAndReplyAsync(m, context, r)
          case None =>
            logWarning("Attempted to request executors before registering with Master.")
            context.reply(false)
        }
      // 转发给master
      case k: KillExecutors =>
        master match {
          case Some(m) => askAndReplyAsync(m, context, k)
          case None =>
            logWarning("Attempted to kill executors before registering with Master.")
            context.reply(false)
        }
    }

    private def askAndReplyAsync[T](
        endpointRef: RpcEndpointRef,
        context: RpcCallContext,
        msg: T): Unit = {
      // Ask a message and create a thread to reply with the result.  Allow thread to be
      // interrupted during shutdown, otherwise context must be notified of NonFatal errors.
      endpointRef.ask[Boolean](msg).andThen {
        case Success(b) => context.reply(b)
        case Failure(ie: InterruptedException) => // Cancelled
        case Failure(NonFatal(t)) => context.sendFailure(t)
      }(ThreadUtils.sameThread)
    }

    override def onDisconnected(address: RpcAddress): Unit = {
      if (master.exists(_.address == address)) {
        logWarning(s"Connection to $address failed; waiting for master to reconnect...")
        markDisconnected()
      }
    }

    override def onNetworkError(cause: Throwable, address: RpcAddress): Unit = {
      if (isPossibleMaster(address)) {
        logWarning(s"Could not connect to $address: $cause")
      }
    }

    /**
     * 通知监听器已经断开连接了.
      * Notify the listener that we disconnected, if we hadn't already done so before.
     */
    def markDisconnected() {
      if (!alreadyDisconnected) {
        listener.disconnected()
        alreadyDisconnected = true
      }
    }
    /** 标记死亡*/
    def markDead(reason: String) {
      if (!alreadyDead.get) {
        // 调用监听器的死亡方法并添加原因
        listener.dead(reason)
        // 设置成员变量
        alreadyDead.set(true)
      }
    }

    override def onStop(): Unit = {
      if (registrationRetryTimer.get != null) {
        registrationRetryTimer.get.cancel(true)
      }
      registrationRetryThread.shutdownNow()
      registerMasterFutures.get.foreach(_.cancel(true))
      registerMasterThreadPool.shutdownNow()
    }

  }
  /** 启动StandaloneAppClient*/
  def start() {
    // Just launch an rpcEndpoint; it will call back into the listener.
    // 向sparkcontext的sparkEnv的RpcEnv注册ClientEndpoint
    endpoint.set(rpcEnv.setupEndpoint("AppClient", new ClientEndpoint(rpcEnv)))
  }
  /** 停止StandaloneAppclient*/
  def stop() {
    if (endpoint.get != null) {
      try {
        val timeout = RpcUtils.askRpcTimeout(conf)
        // 发送StopAppClient消息
        timeout.awaitResult(endpoint.get.ask[Boolean](StopAppClient))
      } catch {
        case e: TimeoutException =>
          logInfo("Stop request to Master timed out; it may already be shut down.")
      }
      endpoint.set(null)
    }
  }

  /**
   * 向master请求所需的所有executor资源
    * Request executors from the Master by specifying the total number desired,
   * including existing pending and running executors.
   *
   * @return whether the request is acknowledged.
   */
  def requestTotalExecutors(requestedTotal: Int): Future[Boolean] = {
    if (endpoint.get != null && appId.get != null) {
      endpoint.get.ask[Boolean](RequestExecutors(appId.get, requestedTotal))
    } else {
      logWarning("Attempted to request executors before driver fully initialized.")
      Future.successful(false)
    }
  }

  /**
   * 向master请求杀死Executor
    * Kill the given list of executors through the Master.
   * @return whether the kill request is acknowledged.
   */
  def killExecutors(executorIds: Seq[String]): Future[Boolean] = {
    if (endpoint.get != null && appId.get != null) {
      // 发送KillExecutors消息
      endpoint.get.ask[Boolean](KillExecutors(appId.get, executorIds))
    } else {
      logWarning("Attempted to kill executors before driver fully initialized.")
      Future.successful(false)
    }
  }

}
