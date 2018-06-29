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

package org.apache.spark.rpc.netty

import org.apache.spark.rpc.{RpcCallContext, RpcEndpoint, RpcEnv}

/**
 * 远程RpcEnv的一个RpcEndpoint,用于查询RpcEndpoint是否存在.
  * 装载远程endpoint引用时使用.
  * An [[RpcEndpoint]] for remote [[RpcEnv]]s to query if an `RpcEndpoint` exists.
 *
 * This is used when setting up a remote endpoint reference.
 */
private[netty] class RpcEndpointVerifier(override val rpcEnv: RpcEnv, dispatcher: Dispatcher)
  extends RpcEndpoint {
  /**
    * 查询当前RpcEndpointVerifier所在RpcEnv中的Dispatcher里是否存在请求中指定名称所对应的RpcEndpoint
    */
  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    // 接收CheckExistence类型消息,匹配name参数,此参数代表查询RpcEndpoint的具体名称
    // 调用dispatcher.verify(name)方法.校验dispatcher的endpoints缓存中是否包含名为name的RpcEndpoint
    // reply回复客户端
    case RpcEndpointVerifier.CheckExistence(name) => context.reply(dispatcher.verify(name))
  }
}

private[netty] object RpcEndpointVerifier {
  val NAME = "endpoint-verifier"

  /** A message used to ask the remote [[RpcEndpointVerifier]] if an `RpcEndpoint` exists. */
  case class CheckExistence(name: String)
}
