package com.rasterfoundry.database

import com.rasterfoundry.common.ast.codec.MapAlgebraCodec._
import com.rasterfoundry.common.ast.MapAlgebraAST._
import com.rasterfoundry.common.ast._
import com.rasterfoundry.common.datamodel._
import com.rasterfoundry.common.datamodel.export._
import com.rasterfoundry.database.Implicits._
import com.rasterfoundry.database.util._

import cats._
import cats.implicits._
import _root_.io.circe._
import _root_.io.circe.syntax._
import doobie._
import doobie.implicits._
import doobie.postgres._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._

import java.util.UUID

object ExportDao extends Dao[Export] {

  val tableName = "exports"

  val selectF: Fragment = fr"""
    SELECT
      id, created_at, created_by, modified_at, modified_by, owner,
      project_id, export_status, export_type,
      visibility, toolrun_id, export_options
    FROM
  """ ++ tableF

  def unsafeGetExportById(exportId: UUID): ConnectionIO[Export] =
    query.filter(exportId).select

  def getExportById(exportId: UUID): ConnectionIO[Option[Export]] =
    query.filter(exportId).selectOption

  def insert(export: Export, user: User): ConnectionIO[Export] = {
    val insertF: Fragment = Fragment.const(s"INSERT INTO ${tableName} (")
    val ownerId = util.Ownership.checkOwner(user, Some(export.owner))
    (insertF ++ fr"""
        id, created_at, created_by, modified_at, modified_by, owner,
        project_id, export_status, export_type,
        visibility, toolrun_id, export_options
      ) VALUES (
        ${UUID.randomUUID}, NOW(), ${user.id}, NOW(), ${user.id}, ${ownerId},
        ${export.projectId}, ${export.exportStatus}, ${export.exportType},
        ${export.visibility}, ${export.toolRunId}, ${export.exportOptions}
      )
    """).update.withUniqueGeneratedKeys[Export](
      "id",
      "created_at",
      "created_by",
      "modified_at",
      "modified_by",
      "owner",
      "project_id",
      "export_status",
      "export_type",
      "visibility",
      "toolrun_id",
      "export_options"
    )
  }

  def update(export: Export, id: UUID, user: User): ConnectionIO[Int] = {
    (fr"UPDATE" ++ tableF ++ fr"SET" ++ fr"""
        modified_at = NOW(),
        modified_by = ${user.id},
        export_status = ${export.exportStatus},
        visibility = ${export.visibility}
      WHERE id = ${id}
    """).update.run
  }

  def getWithStatus(id: UUID,
                    status: ExportStatus): ConnectionIO[Option[Export]] = {
    (selectF ++ fr"WHERE id = ${id} AND export_status = ${status}")
      .query[Export]
      .option
  }

  def getExportDefinition[Source: Encoder: Decoder](
      export: Export,
      user: User): ConnectionIO[ExportDefinition[Source]] = {
    val exportOptions = export.exportOptions.as[ExportOptions] match {
      case Left(e) =>
        throw new Exception(
          s"Did not find options for export ${export.id}: ${e}")
      case Right(eo) => eo
    }

    logger.debug("Decoded export options successfully")

    val outputDefinition: ConnectionIO[OutputDefinition] = for {
      user <- UserDao.getUserById(export.owner)
    } yield {
      OutputDefinition(
        crs = exportOptions.getCrs,
        destination = exportOptions.source.toString,
        dropboxCredential = user.flatMap(_.dropboxCredential.token)
      )
    }

    val exportSource: ConnectionIO[Source] = {

      logger.info(s"Project id when getting input style: ${export.projectId}")
      logger.info(s"Tool run id when getting input style: ${export.toolRunId}")
      (export.projectId, export.toolRunId) match {
        // Exporting a tool-run
        case (_, Some(toolRunId)) =>
          astInput(toolRunId, exportOptions)
        // Exporting a project
        case (Some(projectId), None) =>
          simpleInput(projectId, exportOptions)
        case (None, None) =>
          throw new Exception(
            s"Export Definitions ${export.id} does not have project or ast input defined")
      }
    }

    for {
      _ <- logger.info("Creating output definition").pure[ConnectionIO]
      outputDef <- outputDefinition
      _ <- logger
        .info(s"Created output definition for ${outputDef.destination}")
        .pure[ConnectionIO]
      _ <- logger.info(s"Creating input definition").pure[ConnectionIO]
      sourceDef <- exportSource
      _ <- logger.info("Created input definition").pure[ConnectionIO]
    } yield {
      ExportDefinition(export.id, sourceDef, outputDef)
    }
  }

  /**
    * An AST could include nodes that ask for scenes from the same
    * project, from different projects, or all scenes from a project. This can
    * happen at the same time, and there's nothing illegal about this, we just
    * need to make sure to include all the ingest locations.
    */
  private def astInput(
      toolRunId: UUID,
      exportOptions: ExportOptions
  ): ConnectionIO[AnalysisExportSource] = {
    val ndOverride = ???
    for {
      toolRun <- ToolRunDao.query.filter(toolRunId).select
      _ <- logger.debug("Got tool run").pure[ConnectionIO]
      ast <- {
        toolRun.executionParameters.as[MapAlgebraAST] match {
          case Left(e)              => throw e
          case Right(mapAlgebraAST) => stripMetadata(mapAlgebraAST)
        }
      }.pure[ConnectionIO]
      _ <- logger.debug("Fetched ast").pure[ConnectionIO]
      sceneLocs <- sceneIngestLocs(ast)
      _ <- logger.debug("Found ingest locations for scenes").pure[ConnectionIO]
      projectLocs <- projectIngestLocs(ast)
      _ <- logger
        .debug("Found ingest locations for projects")
        .pure[ConnectionIO]
    } yield {
      //ASTInput(ast, sceneLocs, projectLocs)
      val ast2 = ???
      val params = ???
      AnalysisExportSource(
        exportOptions.resolution,
        exportOptions.mask.get.geom,
        ast2,
        params //String -> List[(location, band, ndOverride)]
      )
    }
  }

  private def simpleInput(
      projectId: UUID,
      exportOptions: ExportOptions
  ): ConnectionIO[MosaicExportSource] = {
    SceneToProjectDao.getMosaicDefinition(projectId).compile.toList map { mds =>
      // we definitely need NoData but it isn't obviously available :(
      val ndOverride: Option[Double] = ???
      val layers = mds.collect {
        case md =>
          (md.ingestLocation, exportOptions.bands).mapN {
            case (ingestLocation, bands) =>
              (ingestLocation, bands.toList, ndOverride)
          }
      }.flatten
      MosaicExportSource(
        exportOptions.resolution,
        exportOptions.mask.get.geom,
        layers
      )
    }
  }

  private def stripMetadata(ast: MapAlgebraAST): MapAlgebraAST = ast match {
    case astLeaf: MapAlgebraAST.MapAlgebraLeaf =>
      astLeaf.withMetadata(NodeMetadata())
    case astNode: MapAlgebraAST =>
      val args = ast.args.map(stripMetadata)
      ast.withMetadata(NodeMetadata()).withArgs(args)
  }

  /** Obtain the ingest locations for all Scenes and Projects which are
    * referenced in the given [[EvalParams]]. If even a single Scene anywhere is
    * found to have no `ingestLocation` value, the entire operation fails.
    *
    * @note Scenes are represented with a map from scene ID to ingest location.
    * @note Projects are represented with a map from project ID to a map from scene ID to
    *       ingest location
    */
  private def sceneIngestLocs(
      ast: MapAlgebraAST
  ): ConnectionIO[Map[String, String]] = {

    val sceneIds: Set[UUID] = ast.tileSources.flatMap {
      case s: SceneRaster => Some(s.sceneId)
      case _              => None
    }

    logger.debug(s"Working with this many scenes: ${sceneIds.size}")

    for {
      scenes <- sceneIds.toList.toNel match {
        case Some(ids) =>
          SceneDao.query
            .filter(sceneIds.toList.toNel.map(ids => Fragments.in(fr"id", ids)))
            .list
        case _ => List.empty[Scene].pure[ConnectionIO]
      }
    } yield {
      scenes.flatMap { scene =>
        scene.ingestLocation.map((scene.id.toString, _))
      }.toMap
    }
  }

  private def projectIngestLocs(
      ast: MapAlgebraAST): ConnectionIO[Map[UUID, List[(UUID, String)]]] = {
    val projectIds: Set[UUID] = ast.tileSources.flatMap {
      case s: ProjectRaster => Some(s.projId)
      case _                => None
    }

    logger.debug(s"Working with this many projects: ${projectIds.size}")

    val sceneProjectSelect = fr"""
    SELECT stp.project_id, array_agg(stp.scene_id), array_agg(scenes.ingest_location)
    FROM
      scenes_to_projects as stp
    LEFT JOIN
      scenes
    ON
      stp.scene_id = scenes.id
    """ ++ Fragments.whereAndOpt(
      projectIds.toList.toNel.map(ids => Fragments.in(fr"stp.project_id", ids))
    ) ++ fr"GROUP BY stp.project_id"
    val projectSceneLocs = for {
      stps <- sceneProjectSelect
        .query[(UUID, List[UUID], List[String])]
        .to[List]
    } yield {
      stps.flatMap {
        case (pID, sID, loc) =>
          if (loc.isEmpty) {
            None
          } else {
            Some((pID, sID zip loc))
          }
      }.toMap
    }
    projectSceneLocs
  }
}
