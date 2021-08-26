// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package test

import io.circe.literal._

class MutationSuite extends OdbSuite {

  queryTest(
    query = """
      mutation BulkEditConstraints($bulkEditConstraints: BulkEditConstraintSetInput!) {
        updateConstraintSet(input: $bulkEditConstraints) {
          id
          constraintSet {
            skyBackground
          }
        }
      }
    """,
    expected = json"""
      {
        "updateConstraintSet" : [
          {
            "id" : "o-3",
            "constraintSet" : {
              "skyBackground" : "GRAY"
            }
          },
          {
            "id" : "o-4",
            "constraintSet" : {
              "skyBackground" : "GRAY"
            }
          }
        ]
      }
    """,
    variables = Some(json"""
      {
        "bulkEditConstraints": {
          "constraintSet": {
            "skyBackground": "GRAY"
          },
          "observationIds": [ "o-3", "o-4" ]
        }
      }
    """)
  )

  queryTest(
    query = """
      mutation UpdateSiderealTarget($editSidereal: EditSiderealInput!) {
        updateSiderealTarget(input: $editSidereal) {
          id
          name
          tracking {
            __typename
            ... on Sidereal {
              coordinates {
                ra { hms }
                dec { dms }
              }
              properMotion {
                ra { microarcsecondsPerYear }
              }
              parallax { microarcseconds }
            }

          }
        }
      }
    """,
    expected = json"""
      {
        "updateSiderealTarget" : {
          "id" : "t-2",
          "name" : "Two",
          "tracking" : {
            "__typename" : "Sidereal",
            "coordinates" : {
              "ra" : {
                "hms" : "02:00:00.000000"
              },
              "dec" : {
                "dms" : "+02:00:00.000000"
              }
            },
            "properMotion" : {
              "ra" : {
                "microarcsecondsPerYear" : 0
              }
            },
            "parallax" : null
          }
        }
      }
    """,
    variables = Some(json"""
      {
        "editSidereal": {
            "targetId": "t-2",
            "name":     "Two",
            "ra":       { "hours": 2.0 },
            "dec":      { "dms":   "02:00:00.00" },
            "parallax": null
          }
      }
    """)
  )

  queryTestFailure(
    query =
      """
        mutation BulkEditConstraints($bulkEditConstraints: BulkEditConstraintSetInput!) {
          updateConstraintSet(input: $bulkEditConstraints) {
            id
            constraintSet {
              skyBackground
              elevationRange {
                ... on AirMassRange {
                  min
                  max
                }
              }
            }
          }
        }
      """,
    errors = List(
      "'min' out of range: must be 1.0 <= min <= 3.0"
    ),
    variables = Some(json"""
      {
        "bulkEditConstraints": {
          "observationIds": [ "o-3" ],
          "constraintSet": {
            "skyBackground": "GRAY",
            "elevationRange": {
              "airmassRange": {
                "min": 0.0,
                "max": 2.0
              }
            }
          }
        }
      }
      """)
  )

}