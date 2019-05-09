package com.rasterfoundry.database.meta

import com.rasterfoundry.datamodel._

import doobie._

trait PermissionsMeta {
  implicit val ObjectAccessControlRuleMeta: Meta[ObjectAccessControlRule] =
    Meta[String]
      .timap(ObjectAccessControlRule.unsafeFromObjAcrString)(_.toObjAcrString)
}
