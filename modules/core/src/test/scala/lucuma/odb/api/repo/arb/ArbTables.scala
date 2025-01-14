// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.repo
package arb

import lucuma.core.arb.ArbTime
import lucuma.core.model.{Atom, ExecutionEvent, Observation, Program, Step, Target}
import lucuma.odb.api.model.{AtomModel, ExecutionEventModel, InstrumentConfigModel, ObservationModel, ProgramModel, StepModel}
import lucuma.core.util.Gid
import lucuma.odb.api.model.SequenceModel.SequenceType.{Acquisition, Science}
import lucuma.odb.api.model.arb._
import cats.data.{Nested, State}
import cats.kernel.instances.order._
import cats.syntax.all._
import lucuma.odb.api.model.targetModel.{TargetEnvironmentModel, TargetModel}
import org.scalacheck._
import org.scalacheck.cats.implicits._
import org.scalacheck.Arbitrary.arbitrary

import java.time.Instant

import scala.collection.immutable.{SortedMap, SortedSet}

trait ArbTables extends SplitSetHelper {

  import ArbExecutionEventModel._
  import ArbInstrumentConfigModel._
  import ArbObservationModel._
  import ArbProgramModel._
  import ArbTargetModel._
  import ArbTime._

  private def map[I: Gid, M: Arbitrary](updateId: (M, I) => M): Gen[SortedMap[I, M]] =
    arbitrary[List[M]]
      .map { lst =>
        SortedMap.from(
          lst
            .zipWithIndex
            .map { case (m, i) =>
              val gid = Gid[I].fromLong.getOption(i.toLong + 1).get
              (gid, updateId(m, gid))
            }
        )
      }

  private def mapWithValidPid[I: Gid, M: Arbitrary](
    pids: List[Program.Id],
    updateId: (M, I) => M,
    updatePid: (M, Program.Id) => M
  ): Gen[SortedMap[I, M]] =

    if (pids.isEmpty)
      Gen.const(SortedMap.empty[I, M])
    else
      for {
        ms <- map[I, M](updateId)
        ps <- Gen.listOfN(ms.size, Gen.oneOf(pids))
      } yield
        SortedMap.from(
          ms.toList.zip(ps).map { case ((i, m), pid) => (i, updatePid(m, pid)) }
        )

  private def mapObservations(
    pids: List[Program.Id],
    ts:   SortedMap[Target.Id, TargetModel]
  ): Gen[SortedMap[Observation.Id, ObservationModel]] = {

    // valid targets for each program id
    val validTids: Map[Program.Id, List[Target.Id]] =
      ts.values.toList.groupBy(_.programId).view.mapValues(_.map(_.id)).toMap

    for {
      // observation map where every observation references a valid program
      m <- mapWithValidPid[Observation.Id, ObservationModel](
             pids,
             (m, i)   => m.copy(id = i),
             (m, pid) => m.copy(programId = pid)
           )

      // observations where target asterisms refer to valid existing targets
      // for the program
      os <- m.values.toList.traverse { om =>
              Gen.someOf(validTids.getOrElse(om.programId, List.empty))
                  .map { ts =>
                    ObservationModel.targetEnvironment
                      .andThen(TargetEnvironmentModel.asterism)
                      .replace(SortedSet.from(ts))(om)
                  }
            }

    } yield SortedMap.from(os.fproductLeft(_.id))

  }

  private def mapPrograms: Gen[SortedMap[Program.Id, ProgramModel]] =
    map[Program.Id, ProgramModel]((p, i) => p.copy(id = i))

  private def mapTargets(
    pids: List[Program.Id]
  ): Gen[SortedMap[Target.Id, TargetModel]] =
    mapWithValidPid[Target.Id, TargetModel](
      pids,
      (m, i)   => m.copy(id = i),
      (m, pid) => m.copy(programId = pid)
    )

  private def lastGid[I: Gid](ms: SortedMap[I, _]): I =
    if (ms.isEmpty) Gid[I].minBound else ms.lastKey

  implicit val arbTables: Arbitrary[Tables] =
    Arbitrary {
      for {
        ps <- mapPrograms
        ts <- mapTargets(ps.keys.toList)
        os <- mapObservations(ps.keys.toList, ts)
        ids = Ids(
          0L,
          lastGid[Atom.Id](SortedMap.empty[Atom.Id, AtomModel[_]]),
          lastGid[ExecutionEvent.Id](SortedMap.empty[ExecutionEvent.Id, ExecutionEventModel]),
          lastGid[Observation.Id](os),
          lastGid[Program.Id](ps),
          lastGid[Step.Id](SortedMap.empty[Step.Id, StepModel[_]]),
          lastGid[Target.Id](ts)
        )
      } yield Tables(ids, SortedMap.empty, SortedMap.empty, os, ps, SortedMap.empty, ts)
    }

  /**
   * Arbitrary tables with sequences is slow and since sequences are not always
   * needed for testing, I've made it not implicit.
   */
  val arbTablesWithSequences: Arbitrary[Tables] = {

    def tablesWithSequences(t: Tables, c: List[Option[InstrumentConfigModel.Create]]): Tables = {
        // Create an option random sequence for each observation.
        val (tʹ, a) = Nested(c).traverse(_.create[State[Tables, *], Tables](TableState)).run(t).value
        val icms    = a.value.map(_.flatMap(_.toOption))

        // Update the observations to contain the random sequence.
        Tables.observations.modify { obsMap =>
          val icmMap = obsMap.keys.zip(icms).toMap
          obsMap.transform((id, o) => o.copy(config = icmMap.get(id).flatten.map(_.toReference)))
        }(tʹ)
    }

    Arbitrary {
      for {
        t <- arbTables.arbitrary
        c <- Gen.listOfN[Option[InstrumentConfigModel.Create]](
               t.observations.size,
               Gen.option(arbValidInstrumentConfigModelCreate.arbitrary)
             )
      } yield tablesWithSequences(t, c)
    }
  }

  val arbTablesWithSequencesAndEvents: Arbitrary[Tables] = {

    def addEventsForObservation(t: Tables)(o: ObservationModel): Gen[State[Tables, Unit]] = {
      val acqAtoms = o.config.toList.flatMap(_.acquisition.atoms)
      val sciAtoms = o.config.toList.flatMap(_.science.atoms)
      val acqSteps = acqAtoms.flatMap(aid => t.atoms(aid).steps.toList)
      val sciSteps = sciAtoms.flatMap(aid => t.atoms(aid).steps.toList)

      for {
        seqCnt        <- smallSize
        seqEvents     <- Gen.listOfN(seqCnt, arbSequenceEventAdd(o.id).arbitrary)

        acqIdSize     <- tinyPositiveSize
        acqIds        <- Gen.someOf[Step.Id](acqSteps).map(_.toList.take(acqIdSize))

        sciIdSize     <- tinyPositiveSize
        sciIds        <- Gen.someOf[Step.Id](sciSteps).map(_.toList.take(sciIdSize))

        acqCnts       <- acqIds.traverse(sid => tinySize.map(i => (i, sid)))
        acqStepEvents <- acqCnts.flatTraverse { case (cnt, sid) => Gen.listOfN(cnt, arbStepEventAdd(o.id, sid, Acquisition).arbitrary) }

        sciCnts       <- sciIds.traverse(sid => tinySize.map(i => (i, sid)))
        sciStepEvents <- sciCnts.flatTraverse { case (cnt, sid) => Gen.listOfN(cnt, arbStepEventAdd(o.id, sid, Science).arbitrary) }

        dstCnts       <- (acqIds ++ sciIds).traverse(sid => tinySize.map(i => (i, sid)))
        dstEvents     <- dstCnts.flatTraverse { case (cnt, sid) => Gen.listOfN(cnt, arbDatasetEventAdd(o.id, sid).arbitrary) }

        received      <- arbitrary[Instant]
      } yield
        for {
          _ <- seqEvents.traverse(_.add[State[Tables, *], Tables](TableState, received)).void
          _ <- acqStepEvents.traverse(_.add[State[Tables, *], Tables](TableState, received)).void
          _ <- sciStepEvents.traverse(_.add[State[Tables, *], Tables](TableState, received)).void
          _ <- dstEvents.traverse(_.add[State[Tables, *], Tables](TableState, received)).void
        } yield ()
    }

    Arbitrary {
      for {
        t <- arbTablesWithSequences.arbitrary
        add = addEventsForObservation(t)(_)
        e <- t.observations.values.toList.traverse(add).map(_.sequence_)
      } yield e.runS(t).value
    }
  }
}

object ArbTables extends ArbTables
