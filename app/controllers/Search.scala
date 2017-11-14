package controllers

import de.proteinevolution.models.database.jobs.Job
import play.Logger
import models.UserSessions
import play.api.cache._
import play.api.libs.json.Json
import javax.inject.{ Inject, Singleton }

import de.proteinevolution.common.LocationProvider
import de.proteinevolution.models.Constants
import play.modules.reactivemongo.{ ReactiveMongoApi, ReactiveMongoComponents }
import reactivemongo.bson.BSONDocument

import scala.concurrent.ExecutionContext.Implicits.global
import de.proteinevolution.models.search.JobDAO
import models.tools.ToolFactory
import de.proteinevolution.db.MongoStore
import play.api.mvc._

import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.matching.Regex

@Singleton
final class Search @Inject()(@NamedCache("userCache") implicit val userCache: SyncCacheApi,
                             implicit val locationProvider: LocationProvider,
                             userSessions: UserSessions,
                             val reactiveMongoApi: ReactiveMongoApi,
                             mongoStore: MongoStore,
                             toolFactory: ToolFactory,
                             val jobDao: JobDAO,
                             constants: Constants,
                             cc: ControllerComponents)
    extends AbstractController(cc)
    with ReactiveMongoComponents
    with CommonController {

  def getToolList: Action[AnyContent] = Action {
    Ok(
      Json.toJson(
        toolFactory.values.values
          .filterNot(_.toolNameShort == "hhpred_manual")
          .map(a => Json.obj("long" -> a.toolNameLong, "short" -> a.toolNameShort))
      )
    )
  }

  /**
    * fetches data for a given query
    *
    * if no tool is found for a given query,
    * it looks for jobs which belong to the current user.
    * only jobIDs that belong to the user are autocompleted
    *
    * @param queryString_
    * @return
    */
  def autoComplete(queryString_ : String): Action[AnyContent] = Action.async { implicit request =>
    userSessions.getUser.flatMap { user =>
      val queryString = queryString_.trim()
      val tools: List[models.tools.Tool] = toolFactory.values.values
        .filter(t => queryString.toLowerCase.r.findFirstIn(t.toolNameLong.toLowerCase()).isDefined)
        .filter(tool => tool.toolNameShort != "hhpred_manual")
        .toList
      // Find out if the user looks for a certain tool or for a jobID
      if (tools.isEmpty) {
        // Grab Job ID auto completions
        mongoStore.findJobs(BSONDocument(Job.JOBID -> BSONDocument("$regex" -> queryString))).flatMap { jobs =>
          val jobsFiltered = jobs.filter(job => job.ownerID.contains(user.userID))
          if (jobsFiltered.isEmpty) {
            mongoStore
              .findJob(BSONDocument(Job.JOBID -> queryString))
              .map(x => Ok(Json.toJson(List(x.map(_.cleaned())))))
          } else {
            Future.successful(Ok(Json.toJson(jobsFiltered.map(_.cleaned()))))
          }
        }
      } else {
        // Find the Jobs with the matching tool
        mongoStore
          .findJobs(
            BSONDocument(Job.OWNERID -> user.userID, Job.TOOL -> BSONDocument("$in" -> tools.map(_.toolNameShort)))
          )
          .map { jobs =>
            jobs.map(_.cleaned())
          }
          .map(jobJs => Ok(Json.toJson(jobJs)))
      }
    }
  }

  def existsTool(queryString: String): Action[AnyContent] = Action.async { implicit request =>
    userSessions.getUser.map { user =>
      if (toolFactory.isTool(queryString)) {
        Ok(Json.toJson(true))
      } else {
        NotFound
      }
    }
  }

  def get: Action[AnyContent] = Action.async { implicit request =>
    // Retrieve the jobs from the DB
    userSessions.getUser.flatMap { user =>
      mongoStore
        .findJobs(BSONDocument(Job.OWNERID -> user.userID, Job.DELETION -> BSONDocument("$exists" -> false)))
        .map { jobs =>
          NoCache(Ok(Json.toJson(jobs.map(_.jobManagerJob()))))
        }
    }
  }

  /**
    * Returns a json object containing both the last updated job and the most recent total number of jobs.
    *
    * @return
    */
  def getIndexPageInfo: Action[AnyContent] = Action.async { implicit request =>
    userSessions.getUser.flatMap { user =>
      mongoStore
        .findSortedJob(
          BSONDocument(BSONDocument(Job.DELETION -> BSONDocument("$exists" -> false)),
                       BSONDocument(Job.OWNERID  -> user.userID)),
          BSONDocument(Job.DATEUPDATED -> -1)
        )
        .map { lastJob =>
          Ok(Json.obj("lastJob" -> lastJob.map(_.cleaned())))
        }
    }
  }

  /**
    * Looks for a jobID in the DB and checks if it is in use
    * if resubmit is true, the return object will also include the highest version jobID
    * @param newJobID
    * @param resubmitForJobID
    * @return
    */
  def checkJobID(newJobID: String, resubmitForJobID : Option[String]): Action[AnyContent] = Action.async {
    // Parse the jobID of the job (it can look like this: 1234XYtz, 1263412, 1252rttr_1, 1244124_12)
    val parentJobID: Option[String] =
      newJobID match {
        case constants.jobIDPattern(mainJobID, _)    =>
          // Check if the main part of the new jobID matches with the (main part) of the oldJobID
          resubmitForJobID match {
            case Some(constants.jobIDPattern(oldJobID, _))       => if (mainJobID == oldJobID) Some(mainJobID) else None
            case Some(constants.jobIDNoVersionPattern(oldJobID)) => if (mainJobID == oldJobID) Some(mainJobID) else None
            case _                                               => None
          }
        case constants.jobIDNoVersionPattern(mainJobID) => Some(mainJobID)
        case _                                          => None
      }

    parentJobID match {
      case None =>
        Logger.info(s"[Search.checkJobID] invalid jobID: ${newJobID.trim}${resubmitForJobID.map(a=> s" Resubmit jobID: $a").getOrElse("")}")
        Future.successful(Ok(Json.obj("exists" -> true)))
      case Some(mainJobID) =>
        val jobIDSearch = s"$mainJobID(${constants.jobIDVersioningCharacter}[0-9]{1,3})?"
        Logger.info(s"[Search.checkJobID] JobID suggestions:${resubmitForJobID.map(a=>s"\nOld jobID: $a").getOrElse("")} \nMain part of the jobID: $mainJobID \nCurrent job ID: $newJobID \nSearching for: $jobIDSearch")
        mongoStore.findJobs(BSONDocument(Job.JOBID -> BSONDocument("$regex" -> jobIDSearch))).map { jobs =>
          if (!jobs.map(_.jobID).contains(newJobID)) {
            Logger.info(s"[Search.checkJobID] Found no jobs for the jobID $newJobID.")
            Ok(Json.obj("exists" -> false))
          } else {
            if (resubmitForJobID.nonEmpty) {
              // Check if there is a versioned job already - if so, take the highest version and add one
              Logger.info(s"[Search.checkJobID] Found ${jobs.length} Jobs: ${jobs.map(_.jobID).mkString(",")}")
              val jobVersions = jobs.map { job =>
                job.jobID match {
                  case constants.jobIDPattern(_, v) =>
                    try { v.toInt } catch { case _     => 0 }
                  case _                               => 0
                }
              }
              val version: Int = 1 + jobVersions.sorted.fold(1)((versionBeforeGap, biggerVersion) =>
                if (versionBeforeGap + 1 >= biggerVersion) biggerVersion
                else versionBeforeGap
              )
              Logger.info(s"[Search.checkJobID] Resubmitting jobID version: $version for $mainJobID")
              Ok(Json.obj(
                "exists"    -> true,
                "version"   -> version,
                "suggested" -> s"$mainJobID${constants.jobIDVersioningCharacter}$version"
              ))
            } else {
              // Just check if the jobID is taken, it is a regular job
              Logger.info(s"[Search.checkJobID] Found a similiar job for $mainJobID")
              Ok(Json.obj("exists" -> true))
            }
          }
        }
    }
  }
}
