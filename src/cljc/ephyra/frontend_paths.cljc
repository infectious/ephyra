(ns ephyra.frontend-paths
  "Routing paths handled by the frontend")


(def frontend-routes [["/" :landing]
                      ; New routes, with :focus.
                      ["/:focus/:tab" :main-report]
                      ["/:focus/:tab/job-history/:job-name" :job-history]
                      ["/:focus/:tab/job-history/:job-name/:uuid" :job-invocation]

                      ; Old routes - focus should be supplied by the handler and the route
                      ; rewritten.
                      ["/:tab" :main-report]
                      ["/:tab/job-history/:job-name" :job-history]
                      ["/:tab/job-history/:job-name/:uuid" :job-invocation]
                      ])

(def frontend-paths (map first frontend-routes))
