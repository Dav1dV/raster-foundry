package com.azavea.rf.config

import scala.concurrent.{Future, ExecutionContext}
import com.azavea.rf.utils.Config
import com.azavea.rf.database.Database
import com.azavea.rf.AkkaSystem

case class AngularConfig(clientId: String, auth0Domain: String)

object AngularConfigService extends AkkaSystem.LoggerExecutor with Config {
  def getConfig():
      AngularConfig = {
    return AngularConfig(auth0ClientId, auth0Domain)
  }
}
