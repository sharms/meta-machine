(ns meta-machine.core
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh with-sh-dir]]
            [taoensso.timbre :as timbre]
            [environ.core :refer [env]]
            [tentacles
             [issues :as issues]
             [repos :as repos]
             [pulls :as pulls]
             [users :as users]])
  (:gen-class))

(def github-token (env :github-token))
(def repo-directory "/Users/stevenharms/Projects/clj/meta-machine/scratch")
(def verbose false)

;; Command Line
(def cli-options
  [["-b" "--basedir basedir" "Directory to scan"
    :default "_data"]
   ["-v" "--verbose" "Increase debug output"]
   ["-h" "--help" "View this message"]])

(defn usage [options-summary]
  (->> ["This program magically creates pull requests for the team API"
        ""
        "flags           vars    default description"
        options-summary
        ]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn parse-file-w-regex [file regex]
  (with-open [rdr (io/reader file)]
    (let [matched-regex (filter #(re-find regex %) (line-seq rdr))]
      (doall matched-regex))))

(defn fork-github-repo [github-token user repo]
  (timbre/info "Forking " repo)
  (let [url (->> {:oauth-token github-token}
                 (repos/create-fork user repo)
                 (:clone_url))] url))

(defn clone-repo-locally [repo destination]
  (timbre/info "Cloning" repo "to" destination)
  (with-sh-dir destination
    (sh "git" "clone" repo)))

(defn fork-and-clone [user repo]
  (let [url (fork-github-repo github-token user repo)]
    (timbre/info "Sleeping 5 seconds")
    (Thread/sleep 5000)
    (clone-repo-locally url repo-directory)))

(defn classify-full-name-owner-type [fullname]
  (cond (re-find #"(?ix)guild" fullname) "guild"
        (re-find #"(?ix)working" fullname) "working-group"
        :else "project"))

(defn classify-repo-owner-type [repo]
  (cond (re-find #"^wg-" repo) "working-group"
        (re-find #"^g-" repo) "guild"
        :else "project"))

(defn about-yml-path [repo]
  (str repo-directory "/" repo "/.about.yml"))

;; These are not consistent - if the repo is clearly named wg- or g-, defer to it
;; If the repo shows 'project', defer to full-name field instead
(defn retrieve-owner-type [repo]
  (with-open [rdr (io/reader (about-yml-path repo))]
    (let [full-name (or (first (filter #(re-find #"^full_name:" %) (line-seq rdr))) "")
          repo-class (classify-repo-owner-type repo)
          full-name-class (classify-full-name-owner-type full-name)
          owner-type (cond (= "project" repo-class) full-name-class :else repo-class)]
      owner-type)))

(defn get-corrected-about-yml-with-owner-type [repo owner-type]
  (with-open [rdr (io/reader (about-yml-path repo))]
    (let [lines (vec (doall (line-seq rdr)))
          re-typical #"^owner_type:.*$"
          re-commented #"^#.*owner_type:.*$"
          re-insert-after #"^# values: guild, working-group, project$"
          corrected (str "owner_type: " owner-type)]
      (cond (not-empty (filter #(re-find re-typical %) lines)) (map #(string/replace % re-typical corrected) lines)
            (not-empty (filter #(re-find re-commented %) lines)) (map #(string/replace % re-commented corrected) lines)
            (not-empty (filter #(re-find re-insert-after %) lines)) (map #(string/replace % re-insert-after (str re-insert-after "\n" corrected)) lines)
            :else (conj lines corrected))))
)

(defn correct-errors-unknown-owner-type [user repo]
  (timbre/info "Correcting unknown owner_type in" user repo)
  (fork-and-clone user repo)
  (let [owner-type (retrieve-owner-type repo)
        corrected (get-corrected-about-yml-with-owner-type repo owner-type)
        msg (str ".about.yml had an invalid owner type.  This commit sets owner_type: " owner-type)]
    (with-open [wrt (io/writer (about-yml-path repo))]
      (.write wrt (string/join "\n" corrected))
      (.write wrt "\n"))
    (with-sh-dir (str repo-directory "/" repo)
      (sh "git" "commit" "-a" "-m" msg)
      ;;(sh "git" "push")
      )
    ))

(defn errors-unknown-owner-type
  "Parses errors.yml to find ownership issues.
   owner_type must be guild, working-group, project
   https://github.com/18F/about_yml/blob/master/lib/about_yml/schema.json"
  [file]
  (timbre/info "Parsing errors.yml for unknown owner type:" (.getPath file))
  (let [matched (parse-file-w-regex file #"(?<repo>.*): 'unknown owner type:")
        split (map #(string/split % #":") matched)
        repos-full (map #(first %) split)
        repos (map #(string/split % #"/") repos-full)]
    (doseq [[user repo] repos] (correct-errors-unknown-owner-type user repo)))
)

;; File operations
(def file-types {:weekly-progress {:description "A yaml file populated with week by week entries"
                                    :parse false
                                    :extension "yml"}
                 :departments {:description "Departments"
                               :parse true
                               :extension "md"}
                 :guilds {:description "Guilds"
                          :parse true
                          :extension "md"}
                 :layouts {:description "API described via HTML"
                           :parse false
                           :extension "html"}
                 :projects {:description "Projects"
                            :parse true
                            :extension "md"}
                 :team {:description "Team"
                        :parse true
                        :extension "md"}
                 :test {:description "Test"
                        :parse false}
                 :working-groups {:description "Working groups"
                                  :parse true
                                  :extension "md"}
                 :errors {:description "Errors"
                          :parse true
                          :directory "_data"
                          :name "errors"
                          :extension "yml"
                          :actions [errors-unknown-owner-type]}})

(defn get-parsable [m]
  (filter #(= (:parse (first (rest %))) true) m))

(defn get-by-kv [m k v]
  (let [v (cond (string? v) (re-pattern v) :else v)]
    (filter #(re-matches v (k (first (rest %)) "")) m)))

(defn get-by-extension [m ext]
  (get-by-kv m :extension ext))

(defn get-by-directory [m dir]
  (get-by-kv m :directory dir))

(defn get-by-name [m name]
  (get-by-kv m :name name))

(defn get-with-actions [m]
  (map :actions (filter #(contains? % :actions) (for [[_ v] m] v))))

(defn map-file [file]
  (let [extension (last (string/split (.getName file) #"\."))
        name (first (string/split (.getName file) #"\."))
        directory (.getParent file)
        files-parsable (get-parsable file-types)
        files-ext (get-by-extension files-parsable extension)
        files-name (get-by-name files-ext name)
        files-dir (get-by-directory files-name directory)
        actions (get-with-actions files-dir)]
    (doseq [f (flatten actions)] (f file))
  )
)

(defn action [file]  
  (println (string/split (.getPath file) #"/"))
  (println "Taking drastic action on " file))

(defn walk [dirpath]
  (doall (map map-file (file-seq (io/file dirpath)))))

(defn parse-errors [] false)
(defn parse-md [] false) 
(defn parse-yml [] false)
(defn validate-github [] false)
(defn validate-team-api [] false)
(defn validate-url [] false)

(defn -main [& args] 
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
     (:help options) (exit 0 (usage summary)))
    (walk "_data"))
  )

