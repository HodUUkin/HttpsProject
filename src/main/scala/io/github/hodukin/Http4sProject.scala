package io.github.hodukin

import cats.*
import cats.effect.*
import cats.implicits.*
import org.http4s.circe.*
import org.http4s.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.dsl.*
import org.http4s.dsl.impl.*
import org.http4s.headers.*
import org.http4s.implicits.*
import org.http4s.server.*

import java.time.Year
import java.util.UUID
import scala.collection.mutable
import scala.util.Try
import scala.concurrent.ExecutionContext.global

object Http4sProject extends IOApp {

  private type Actor = String
  case class Movie(id: String, title: String, year: Int, actors: List[Actor], director: String)
  case class Director(firstName: String, lastName: String) {
    override def toString: String = s"$firstName $lastName"
  }
  case class DirectorDetails(firstName: String, lastName: String, ganre: String)

  val snjl: Movie = Movie(
    "6bcbca1e-efd3-411d-9f7c-14b872444fce",
    "Zack Snyder's Justice League",
    2021,
    List("Henry Cavill", "Gal Godot", "Ezra Miller", "Ben Affleck", "Ray Fisher", "Jason Momoa"),
    "Gosha Snyder"
  )

  val snjl2: Movie = Movie(
    "6bcbca1e-efd3-411d-9f7c-14b872444fcr",
    "Zack Snyder's Justice League",
    2025,
    List("Henry Cavill", "Gal Godot", "Ezra Miller", "Ben Affleck", "Ray Fisher", "Jason Momoa"),
    "Zack Snyder"
  )

  val snjl3: Movie = Movie(
    "6bcbca1e-efd3-411d-9f7c-14b872444fce12",
    "Zack Snyder's Justice League",
    2001,
    List("Henry Cavill", "Gal Godot", "Ezra Miller", "Ben Affleck", "Ray Fisher", "Jason Momoa"),
    "Gosha Snyder"
  )

  val movies: Map[String, Movie] = Map(snjl.id -> snjl, snjl2.id -> snjl2, snjl3.id -> snjl3)

  private def findMovieById(movieId: UUID) =
    movies.get(movieId.toString)

  private def findMoviesByDirector(director: String): List[Movie] =
    movies.values.filter(_.director == director).toList

  implicit val yearQueryParamDecoder: QueryParamDecoder[Year] =
    QueryParamDecoder[Int].emap { yearInt =>
      Try(Year.of(yearInt))
        .toEither
        .leftMap { e =>
          ParseFailure(e.getMessage, e.getMessage)
        }
    }

  object DirectorQueryParamMatcher extends QueryParamDecoderMatcher[String]("director")
  object YearQueryParamMatcher extends OptionalValidatingQueryParamDecoderMatcher[Year]("year")

  def movieRoutes[F[_] : Monad : Async : Concurrent : Timer]: HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] {
      case GET -> Root / "movies" :? DirectorQueryParamMatcher(director) +& YearQueryParamMatcher(maybeYear) =>
        val movieByDirector = findMoviesByDirector(director)
        maybeYear match {
          case Some(validatedYear) =>
            validatedYear.fold(
              _ => BadRequest("The year was badly formated"),
              year => {
                val movieByDirectorAndYear = movieByDirector.filter(_.year == year.getValue)
                if (movieByDirectorAndYear.isEmpty)
                  Ok(movieByDirector.asJson)
                else
                  Ok(movieByDirectorAndYear.asJson)
              }
            )
          case None => Ok(movieByDirector.asJson)
        }
      case GET -> Root / "movies" / UUIDVar(movieId) / "actors" =>
        findMovieById(movieId).map(_.actors) match {
          case Some(actors) => Ok(actors.asJson)
          case _ => NotFound(s"No movie with id $movieId found in the database")
        }
    }
  }

  object DirectorPath {
    def unapply(str: String): Option[Director] = {
      Try {
        val tokens = str.split(" ")
        Director(tokens(0), tokens(1))
      }.toOption
    }
  }

  val directorDetailsDB: mutable.Map[Director, DirectorDetails] =
    mutable.Map(Director("Григорий", "Пупкин") -> DirectorDetails("Григорий", "Пупкин", "Hentai"))

  def directorRoutes[F[_] : Monad : Async : Concurrent : Timer]: HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] {
      case GET -> Root / "director" / DirectorPath(director) =>
        directorDetailsDB.get(director) match {
          case Some(dirDetails) => Ok(dirDetails.asJson)
          case _ => NotFound(s"No director '$director' found")
        }
    }
  }

  def allRoutes[F[_] : Monad : Async : Concurrent : Timer]: HttpRoutes[F] = {
    movieRoutes[F] <+> directorRoutes[F]
  }

  def allRoutesComplete[F[_] : Monad : Async : Concurrent : Timer]: HttpApp[F] = {
    allRoutes[F].orNotFound
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val apis = Router(
      "/api" -> movieRoutes[IO],
      "/api/admin" -> directorRoutes[IO]
    ).orNotFound

    BlazeServerBuilder[IO](global)
      .bindHttp(8080, "localhost")
      .withHttpApp(allRoutesComplete)
      .resource
      .use(_ => IO.never)
      .as(ExitCode.Success)


  }

}
