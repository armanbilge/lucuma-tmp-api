// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.model

import lucuma.odb.api.model.arb._

import cats.kernel.laws.discipline.EqTests
import munit.DisciplineSuite

final class WavelengthModelSuite extends DisciplineSuite {

  import ArbWavelengthModel._

  checkAll("WavelengthModel.Input", EqTests[WavelengthModel.Input].eqv)

}
