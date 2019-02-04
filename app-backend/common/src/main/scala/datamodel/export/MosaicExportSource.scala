package com.rasterfoundry.common.datamodel.export

import geotrellis.vector.Polygon
import _root_.io.circe._
import _root_.io.circe.generic.semiauto._

// layers includes tuples of the URL/URI of a cog + the list of bands to use
case class MosaicExportSource(
    zoom: Int,
    area: Polygon,
    layers: List[(String, List[Int], Option[Double])]
)

object MosaicExportSource {
  implicit val encoder = deriveEncoder[MosaicExportSource]
  implicit val decoder = deriveDecoder[MosaicExportSource]
}
