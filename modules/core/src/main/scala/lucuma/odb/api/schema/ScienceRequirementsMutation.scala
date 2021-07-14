// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.schema

import lucuma.odb.api.model.ScienceRequirementsModel

import sangria.macros.derive._
import sangria.schema._
import lucuma.odb.api.model.SpectroscopyScienceRequirementsModel

trait ScienceRequirementsMutation {

  import ScienceRequirementsSchema._
  import WavelengthSchema._
  import RefinedSchema._
  import syntax.inputtype._

  implicit val InputObjectTypeSpectroscopyRequirements: InputObjectType[SpectroscopyScienceRequirementsModel.Input] =
    deriveInputObjectType[SpectroscopyScienceRequirementsModel.Input](
      InputObjectTypeName("SpectroscopyScienceRequirementsInput"),
      InputObjectTypeDescription("Spectroscopy science requirements params")
    )

  implicit val InputObjectTypeScienceRequirementsCreate: InputObjectType[ScienceRequirementsModel.Create] =
    deriveInputObjectType[ScienceRequirementsModel.Create](
      InputObjectTypeName("ScienceRequirementsInput"),
      InputObjectTypeDescription("Science requirement input params")
    )

  implicit val InputObjectTypeScienceRequirementsEdit: InputObjectType[ScienceRequirementsModel.Edit] =
    deriveInputObjectType[ScienceRequirementsModel.Edit](
      InputObjectTypeName("EditScienceRequirementsInput"),
      InputObjectTypeDescription("Edit science requirements"),
      ReplaceInputField("mode", EnumTypeScienceMode.notNullableField("mode")),
      ReplaceInputField("spectroscopyRequirements", InputObjectTypeSpectroscopyRequirements.notNullableField("spectroscopyRequirements")),
    )

}

object ScienceRequirementsMutation extends ScienceRequirementsMutation
