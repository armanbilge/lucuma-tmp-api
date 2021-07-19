// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.model

import lucuma.core.util.Enumerated

sealed abstract class SpectroscopyCapabilities extends Product with Serializable

object SpectroscopyCapabilities {
  case object NodAndShuffle extends SpectroscopyCapabilities
  case object Polarimetry   extends SpectroscopyCapabilities
  case object Corongraphy   extends SpectroscopyCapabilities

  implicit val ConfigurationModeEnumerated: Enumerated[SpectroscopyCapabilities] =
    Enumerated.of(NodAndShuffle, Polarimetry, Corongraphy)
}
