package com.rasterfoundry.batch.cogMetadata

import com.rasterfoundry.batch.Job
import com.rasterfoundry.common.{AWSLambda, RollbarNotifier}
import com.rasterfoundry.datamodel.ProjectLayer
import com.rasterfoundry.database.ProjectLayerDao
import com.rasterfoundry.database.Implicits._
import com.rasterfoundry.database.util.RFTransactor.xaResource

import cats.effect.IO
import cats.implicits._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream

object OverviewBackfill extends Job with RollbarNotifier {
  val name = "backfill-layer-overviews"

  def getProjectLayerSceneCount(
      projectLayer: ProjectLayer
  ): Stream[ConnectionIO, (ProjectLayer, Int)] =
    fr"select count(1) from scenes_to_layers where project_layer_id = ${projectLayer.id}"
      .query[Int]
      .stream map { (projectLayer, _) }

  /** We know suppressing this warning is fine, because the query for project layers
    * explicitly filters out any that don't have a project id
    */
  @SuppressWarnings(Array("OptionGet"))
  def kickoffOverviewGeneration(
      projectLayer: ProjectLayer
  ): IO[Unit] = IO {
    logger.info(
      s"Kicking off lambda overview generation for layer ${projectLayer.id}")
    if (AWSLambda.runLambda) {
      AWSLambda
        .kickoffLayerOverviewCreate(
          projectLayer.projectId.get,
          projectLayer.id,
          "RequestResponse"
        )
    } else {
      logger.info("Sleeping for 10 seconds to pretend to do work")
      Thread.sleep(10000)
    }
  }

  // Giant number inside listQ is because listQ needs a limit parameter, but we don't actually
  // want to limit
  val projectLayers: Stream[ConnectionIO, ProjectLayer] =
    ProjectLayerDao.query
      .filter(fr"project_id IS NOT NULL")
      .filter(fr"overviews_location IS NULL")
      .listQ(1000000)
      .stream

  val projectLayersWithSceneCounts: ConnectionIO[List[(ProjectLayer, Int)]] =
    projectLayers
      .flatMap(getProjectLayerSceneCount)
      .filter({
        case (_, n) => n <= 300 && n > 0
      })
      .compile
      .to[List]

  def runJob(args: List[String]): IO[Unit] = {
    xaResource
      .use(
        t =>
          projectLayersWithSceneCounts
            .transact(t)
            .flatMap { layers =>
              layers.parTraverse({
                case (layer, _) =>
                  kickoffOverviewGeneration(layer).attempt
              })
          }
      )
      .map { results =>
        logger.info(
          s"Backfilled overviews for ${results.length} project layers")
      }
  }
}
