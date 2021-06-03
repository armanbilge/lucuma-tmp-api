// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.schema

import lucuma.odb.api.model.{DatasetModel, ExecutedStepModel}
import lucuma.odb.api.repo.OdbRepo

import cats.effect.Effect
import cats.syntax.all._
import eu.timepit.refined.types.all.PosInt
import sangria.schema._

object ExecutedStepSchema {

  import context._
  import Paging._

  import DatasetSchema.{ DatasetConnectionType, IndexCursor }
  import AtomSchema.AtomInterfaceType
  import StepSchema.{StepIdType, StepInterfaceType}

  def ExecutedStepType[F[_]: Effect](
    typePrefix:  String,
  ): ObjectType[OdbRepo[F], ExecutedStepModel] = {
    ObjectType(
      name        = s"${typePrefix}ExecutedStep",
      description = s"$typePrefix executed step",
      fieldsFn    = () => fields(

        Field(
          name        = "id",
          fieldType   = StepIdType,
          description = Some("Step id"),
          resolve     = _.value.stepId
        ),

        Field(
          name        = "step",
          fieldType   = StepInterfaceType[F],
          description = Some("The executed step itself"),
          resolve     = c => c.step(_.unsafeSelectStep(c.value.stepId))
        ),

        Field(
          name        = "atom",
          fieldType   = AtomInterfaceType[F],
          description = "The atom containing the executed step".some,
          resolve     = c => c.atom(_.unsafeSelectAtom(c.value.atomId))
        ),

        Field(
          name        = "datasets",
          fieldType   = DatasetConnectionType[F],
          description = Some("Datasets associated with this step"),
          arguments   = List(
            ArgumentPagingFirst,
            ArgumentPagingCursor
          ),
          resolve     = c =>
            unsafeSelectPageFuture[F, PosInt, DatasetModel](
              c.pagingCursor("index")(IndexCursor.getOption),
              dm => IndexCursor.reverseGet(dm.index),
              o  => c.ctx.executionEvent.selectDatasetsForStep(c.value.stepId, c.pagingFirst, o)
            )
        )

      )
    )
  }

}
