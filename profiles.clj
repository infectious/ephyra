;; WARNING
;; The profiles.clj file is used for local environment variables, such as database credentials.
;; This file is listed in .gitignore and will be excluded from version control by Git.

{:profiles/dev  {:env {:database-url "postgresql://localhost/ephyra_dev?user=ephyra&password=ephyra"}}
 :profiles/test {:env {:database-url "postgresql://localhost/ephyra_test?user=ephyra&password=ephyra"}}}
