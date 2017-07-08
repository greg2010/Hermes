package org.red.hermes.util

import com.github.theholywaffle.teamspeak3.api.CommandFuture
import com.github.theholywaffle.teamspeak3.api.exception.TS3Exception

import scala.concurrent.{Future, Promise}
import scala.language.implicitConversions

object FutureConverters {

  private class CommandFutureListener[T](p: Promise[T])
    extends CommandFuture.SuccessListener[T] with CommandFuture.FailureListener {
    override def handleSuccess(result: T): Unit = {
      p.success(result)
    }

    override def handleFailure(exception: TS3Exception): Unit = {
      p.failure(exception)
    }
  }

  implicit def commandToScalaFuture[T](commandFuture: CommandFuture[T]): Future[T] = {
    val p = Promise[T]
    commandFuture.onSuccess(new CommandFutureListener[T](p))
    commandFuture.onFailure(new CommandFutureListener[T](p))
    p.future
  }
}
