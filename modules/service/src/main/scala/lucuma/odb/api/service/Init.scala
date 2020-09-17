// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.service

import lucuma.odb.api.model.{AsterismModel, ObservationModel, ProgramModel, RightAscensionModel, TargetModel}
import lucuma.odb.api.repo.OdbRepo
import lucuma.core.math.{Declination, Epoch, ProperVelocity, RadialVelocity}
import cats.effect.Sync
import cats.implicits._

object Init {

  /**
   * Initializes a (presumably) empty ODB with some demo values.
   */
  def initialize[F[_]: Sync](repo: OdbRepo[F]): F[Unit] =
    for {
      p  <- repo.program.insert(
              ProgramModel.Create(
                Some("Observing Stars in Constellation Orion for No Particular Reason")
              )
            )
      t0 <- repo.target.insertSidereal(
              TargetModel.CreateSidereal(
                List(p.id),
                "Betelgeuse",
                RightAscensionModel.Input.fromHms("05:55:10.305"),
                Declination.fromStringSignedDMS.unsafeGet("07:24:25.43"),
                Some(Epoch.J2000),
                Some(ProperVelocity.milliarcsecondsPerYear.reverseGet((BigDecimal("27.54"), BigDecimal("11.3")))),
                RadialVelocity.fromMetersPerSecond.getOption(21884)
              )
            )
      t1 <- repo.target.insertSidereal(
              TargetModel.CreateSidereal(
                List(p.id),
                "Rigel",
                RightAscensionModel.Input.fromHms("05:14:32.272"),
                Declination.fromStringSignedDMS.unsafeGet("-08:12:05.90"),
                Some(Epoch.J2000),
                Some(ProperVelocity.milliarcsecondsPerYear.reverseGet((BigDecimal("1.31"), BigDecimal("0.5")))),
                RadialVelocity.fromMetersPerSecond.getOption(17687)
              )
            )
      a0 <- repo.asterism.insert(
              AsterismModel.CreateDefault(
                List(p.id),
                None,
                List(t0.id, t1.id)
              )
            )
      _  <- repo.observation.insert(
              ObservationModel.Create(
                p.id,
                Some("First Observation"),
                Some(a0.id)
              )
            )
    } yield ()

}
