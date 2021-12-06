// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.model.targetModel

import lucuma.core.model.{Target, TargetEnvironment}
import cats.Eq
import monocle.{Focus, Lens}


/**
 * TargetModel pairs an id with a `lucuma.core.model.Target` and tracks the
 * target environment in which the target is found.
 */
final case class TargetModel(
  id:                  Target.Id,
  targetEnvironmentId: TargetEnvironment.Id,
  target:              Target
) extends TargetHolder

object TargetModel extends TargetModelOptics {

  implicit val EqTargetModel: Eq[TargetModel] =
    Eq.by { a =>
      (
        a.id,
        a.targetEnvironmentId,
        a.target
      )
    }

}

trait TargetModelOptics { self: TargetModel.type =>

  val target: Lens[TargetModel, Target] =
    Focus[TargetModel](_.target)

}
