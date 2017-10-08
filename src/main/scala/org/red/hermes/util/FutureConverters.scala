package org.red.hermes.util

import java.util.function.BiFunction

import com.github.theholywaffle.teamspeak3.api.CommandFuture
import com.github.theholywaffle.teamspeak3.api.exception.TS3Exception
import net.dv8tion.jda.core.requests.RequestFuture

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.implicitConversions
import scala.util.control.NonFatal

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

  implicit def requestToScalaFuture[T](requestFuture: RequestFuture[T])(implicit ec: ExecutionContext): Future[T] = {
    Future(requestFuture.get())
  }

  implicit class RichRequestFuture[T](val requestFuture: RequestFuture[T]) extends AnyVal {
    def asScala(implicit ec: ExecutionContext): Future[T] = requestFuture
  }
}
