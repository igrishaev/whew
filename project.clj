(defproject com.github.igrishaev/whew "0.1.0-SNAPSHOT"

  :description
  "Try to tame CompletableFuture"

  :url
  "https://github.com/igrishaev/whew"

  :license
  {:name "The Unlicense"
   :url "https://unlicense.org/"}

  :deploy-repositories
  {"releases"
   {:url "https://repo.clojars.org"
    :creds :gpg}
   "snapshots"
   {:url "https://repo.clojars.org"
    :creds :gpg}}

  :managed-dependencies
  [[org.clojure/clojure "1.11.1"]
   [manifold "0.4.3"]
   [cc.qbits/auspex "1.0.3"]]

  :dependencies
  [[org.clojure/clojure :scope "provided"]]

  :release-tasks
  [["vcs" "assert-committed"]
   ["test"]
   ["change" "version" "leiningen.release/bump-version" "release"]
   ["vcs" "commit"]
   ["vcs" "tag" "--no-sign"]
   ["deploy"]
   ["change" "version" "leiningen.release/bump-version"]
   ["vcs" "commit"]
   ["vcs" "push"]]

  :profiles
  {:dev
   {:dependencies
    [[manifold]
     [cc.qbits/auspex]]

    :global-vars
    {*warn-on-reflection* true
     *assert* true}}})
