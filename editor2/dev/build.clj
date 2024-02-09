(ns build
  (:require [babashka.fs :as fs]
            [babashka.process :as bp]
            [cljs-static.assets :as assets]
            [cljs-static.page :as page]
            [cljs-static.shadow :as shadow]
            [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [edamame.core :as eda]
            [maria.editor.util :as u]
            [re-db.schema :as schema]
            [shadow.cljs.devtools.api :as shadow.api]))

(defn parse-meta [file]
  (let [src (slurp file)
        [_ns name & args :as form] (eda/parse-string src)
        opts (merge {:name name} (dissoc (meta name) :row :col :end-row :end-col))
        [opts args] (if (string? (first args))
                      [(assoc opts :doc (first args)) (rest args)]
                      [opts args])
        [opts args] (if (map? (first args))
                      [(merge opts (first args)) (rest args)]
                      [opts args])
        opts (if-let [title (u/extract-title src :headings-only true)]
               (assoc opts :title title)
               opts)]
    opts))

(defn current-sha
  "Returns SHA for current git commit"
  []
  (-> (bp/sh "git" "rev-parse" "HEAD")
      :out
      str/trim))

(defn read-curriculum-namespaces []
  (mapv #(let [file (fs/file %)
               file-name (fs/file-name file)
               m (parse-meta file)
               hash (assets/md5 (slurp file))]
           {:db/id [:curriculum/name (last (str/split (str (:name m)) #"\."))]
            :file/title (:title m)
            :file/doc (:doc m)
            :file/id (str "curriculum:" file-name)
            :file/hash hash
            :file/name file-name
            :file/url (str "/curriculum/" file-name "?v=" hash)
            :file/provider :file.provider/curriculum})
        (fs/list-dir (fs/file "src/main/maria/curriculum"))))

(defn index-html []
  (page/root "Maria"
             {:meta {:viewport "width=device-width, initial-scale=1"}
              :styles [{:href (assets/path "/editor.css")}]
              :scripts/head [{:src "https://polyfill.io/v3/polyfill.min.js?version=3.111.0&features=URLSearchParams%2CURL"}]
              :props/html {:class "bg-neutral-100"}
              :body [:div#maria-live]
              :scripts/body [{:type "application/re-db:schema"
                              :value (str {:file/id (merge schema/unique-id
                                                           schema/string)
                                           :curriculum/name (merge schema/unique-id
                                                                   schema/string)})}
                             {:type "application/re-db:tx"
                              :value (conj (read-curriculum-namespaces)
                                           {:db/id :maria.cloud/env
                                            :git/sha (current-sha)})}
                             {:src (shadow/module-path :editor :core)}]}))

(def child-process
  "Run a shell command which terminates with the current one, and streams output to stdout"
  (partial bp/process
           {:in :inherit
            :out :inherit
            :err :inherit
            :shutdown bp/destroy-tree}))

(defn tailwind-watch!
  {:shadow.build/stage :flush}
  [state]
  (defonce _tailwind
           (child-process "npx tailwindcss -w -i src/editor.css -o public/editor.css"))
  state)

(defn copy-curriculum! {:shadow.build/stage :flush}
  [state]
  (bp/sh "bb" "copy-curriculum")
  state)

(defn watch
  {:shadow/requires-server true}
  [& args]
  (shadow.api/watch :editor))

(defonce browse-url (memoize browse/browse-url))

(defn browse!
  {:shadow.build/stage :flush}
  [build-state url]
  (browse-url url)
  build-state)