package scalacv.zio

import java.nio.file.Files

import _root_.zio.*
import _root_.zio.test.*
import org.opencv.core.Mat

import scalacv.*

object ImageScopeSpec extends ZIOSpecDefault:
  private def fixture: ZIO[Scope, Throwable, String] =
    ZIO
      .acquireRelease(
        ZIO.attemptBlocking(Files.createTempFile("scalacv-zio-image-scope", ".png"))
      )(path => nativeFinalizer(Files.deleteIfExists(path): Unit))
      .flatMap: path =>
        ZIO.attemptBlocking(Image.blank(8, 8).write(path.toString)).flatMap(fromCv(_)).as(path.toString)

  def spec = suite("scoped image descendants")(
    test("an already closed ZIO scope releases the acquired image") {
      ZIO.scoped {
        for
          _ <- loadNatives
          path <- fixture
          closed <- Scope.make
          _ <- closed.close(Exit.unit)
          acquired <- closed.extend(imageScoped(path)).exit
          released <- acquired match
            case Exit.Success(image) =>
              ZIO
                .attempt(image.mat)
                .exit
                .ensuring(nativeFinalizer(image.close()))
                .map(_.causeOption.exists(_.failureOption.exists(_.isInstanceOf[IllegalStateException])))
            case Exit.Failure(_) => ZIO.succeed(false)
        yield assertTrue(acquired.isSuccess, released)
      }
    },
    test("scopedZIO releases an existing handle in an already closed scope") {
      ZIO.scoped {
        for
          _ <- loadNatives
          image <- ZIO.acquireRelease(ZIO.attemptBlocking(Image.blank(8, 8)))(i => nativeFinalizer(i.close()))
          raw <- ZIO.succeed(image.mat)
          handle <- ZIO.succeed(image.managed)
          _ <- ZIO.addFinalizer(nativeFinalizer(handle.release()))
          closed <- Scope.make
          _ <- closed.close(Exit.unit)
          adopted <- closed.extend(handle.scopedZIO).exit
        yield assertTrue(
          adopted.causeOption.exists(_.defects.exists(_.isInstanceOf[IllegalStateException])),
          handle.isReleased,
          raw.dataAddr() == 0L
        )
      }
    },
    test("copies and moved descendants close with the image scope") {
      ZIO.scoped {
        for
          _ <- loadNatives
          path <- fixture
          observed <- Ref.make(Vector.empty[Mat])
          _ <- ZIO.addFinalizer(observed.get.flatMap(mats => nativeFinalizer(mats.foreach(_.release()))))
          width <- ZIO.scoped {
            for
              image <- imageScoped(path)
              result <- ZIO.attemptBlocking {
                val copy = image.copy.gray
                val moved = image.gray
                (copy.mat, moved.mat, moved.width)
              }
              _ <- observed.set(Vector(result._1, result._2))
            yield result._3
          }
          mats <- observed.get
        yield assertTrue(width == 8, mats.size == 2, mats.forall(_.dataAddr() == 0L))
      }
    },
    test("interruption closes a moved descendant before returning") {
      ZIO.scoped {
        for
          _ <- loadNatives
          path <- fixture
          observed <- Promise.make[Nothing, Mat]
          _ <- ZIO.addFinalizer(observed.poll.flatMap {
            case Some(value) => value.flatMap(mat => nativeFinalizer(mat.release()))
            case None => ZIO.unit
          })
          fiber <- ZIO.scoped {
            for
              image <- imageScoped(path)
              raw <- ZIO.attemptBlocking(image.gray.mat)
              _ <- observed.succeed(raw)
              _ <- ZIO.never
            yield ()
          }.fork
          raw <- observed.await
          exit <- fiber.interrupt
        yield assertTrue(exit.isInterrupted, raw.dataAddr() == 0L)
      }
    }
  )
