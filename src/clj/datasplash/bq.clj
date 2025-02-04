(ns datasplash.bq
  (:require
   [charred.api :as charred]
   [clj-time.coerce :as tc]
   [clj-time.format :as tf]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.walk :refer [postwalk prewalk]]
   [datasplash.core :as ds])
  (:import
   (com.google.api.services.bigquery.model
    TableRow TableFieldSchema TableSchema TimePartitioning
    Clustering)
   (org.apache.beam.sdk Pipeline)
   (org.apache.beam.sdk.io.gcp.bigquery
    BigQueryIO
    BigQueryIO$Write$WriteDisposition BigQueryIO$Write$SchemaUpdateOption
    BigQueryIO$Write$CreateDisposition TableDestination InsertRetryPolicy
    BigQueryIO$Write$Method)
   (org.apache.beam.sdk.values PBegin PCollection)))

(defn read-bq-raw
  [{:keys [query table standard-sql? query-location] :as options} p]
  (let [opts (assoc options :label :read-bq-table-raw)
        ptrans (cond
                 query (cond-> (.fromQuery (BigQueryIO/readTableRows) query)
                               standard-sql? (.usingStandardSql)
                               query-location (.withQueryLocation query-location))
                 table (.from (BigQueryIO/readTableRows) table)
                 :else (throw (ex-info
                               "Error with options of read-bq-table, should specify one of :table or :query"
                               {:options options})))]
    (-> p
        (cond-> (instance? Pipeline p) (PBegin/in))
        (ds/apply-transform
         ptrans
         ds/named-schema opts))))

(defn auto-parse-val
  [v]
  (cond
    (and (string? v) (re-find #"^\d+$" v)) (Long/parseLong v)
    :else v))

(defn table-row->clj
  ([{:keys [auto-parse]} ^TableRow row]
   (let [keyset (.keySet row)]
     (persistent!
      (reduce
       (fn [acc k]
         (assoc! acc (keyword k)
                 (let [raw-v (get row k)]
                   (cond
                     (instance? java.util.List raw-v) (if (instance? java.util.AbstractMap (first raw-v))
                                                        (map #(table-row->clj {:auto-parse auto-parse} %) raw-v)
                                                        (map #(if auto-parse (auto-parse-val %) %) raw-v))
                     (instance? java.util.AbstractMap raw-v) (table-row->clj {:auto-parse auto-parse} raw-v)
                     :else (if auto-parse (auto-parse-val raw-v) raw-v)))))
       (transient {}) keyset))))
  ([row] (table-row->clj {} row)))

(defn coerce-by-bq-val
  [v]
  (cond
    (instance? java.util.Date v) (try (->> (tc/from-long (.getTime v))
                                           (tf/unparse (tf/formatter "yyyy-MM-dd HH:mm:ss")))
                                      (catch Exception e (log/errorf "error when parsing date %s (%s)" v (.getMessage e))))
    (set? v) (into '() v)
    (keyword? v) (name v)
    (symbol? v) (name v)
    :else v))

(defn clean-name
  [s]
  (let [test (number? s)]
    (-> s
        (cond-> test str)
        name
        (str/replace #"-" "_")
        (str/replace #"\?" ""))))

(defn bqize-keys
  "Recursively transforms all map keys to BQ-valid strings."
  {:added "1.1"}
  [m]
  (postwalk (fn [x] (if (map? x) ; only apply to maps
                     (persistent! (reduce (fn [acc [k v]]
                                            (assoc! acc (clean-name k) v))
                                          (transient {})
                                          x))
                     x))
            m))

(defn ^TableRow clj->table-row
  [hmap]
  (let [^TableRow row (TableRow.)]
    (doseq [[k v] hmap]
      (.set row (clean-name k) (coerce-by-bq-val v)))
    row))

(defn ^TableRow clj-nested->table-row
  [hmap]
  (let [clean-map (->> hmap
                       (prewalk coerce-by-bq-val)
                       (bqize-keys))

        my-mapper (org.codehaus.jackson.map.ObjectMapper.)

        ^TableRow row (.readValue my-mapper (ds/write-json-str clean-map) TableRow)]
    row))

(defn- read-bq-clj-transform
  [options]
  (let [safe-opts (dissoc options :name)]
    (ds/ptransform
     :read-bq-to-clj
     [pcoll]
     (->> pcoll
          (read-bq-raw safe-opts)
          (ds/dmap (partial table-row->clj safe-opts) safe-opts)))))

(defn read-bq
  [options ^Pipeline p]
  (let [opts (assoc options :label :read-bq-table)]
    (ds/apply-transform p (read-bq-clj-transform opts) ds/base-schema opts)))

(defn- clj->TableFieldSchema
  [defs transform-keys]
  (for [{:keys [type mode description] field-name :name nested-fields :fields} defs]
    (-> (TableFieldSchema.)
        (.setName (transform-keys (clean-name field-name)))
        (.setType  (str/upper-case (name type)))
        (cond-> mode (.setMode mode))
        (cond-> description (.setDescription description))
        (cond-> nested-fields (.setFields (clj->TableFieldSchema nested-fields transform-keys))))))

(defn ^TableSchema ->schema
  ([defs transform-keys]
   (if (instance? TableSchema defs)
     defs
     (let [fields (clj->TableFieldSchema defs transform-keys)]
       (-> (TableSchema.) (.setFields fields)))))
  ([defs] (->schema defs name)))

(defn ^TimePartitioning ->time-partitioning
  [{:keys [type expiration-ms field require-partition-filter]
    :or   {type :day}}]
  (let [tp (doto (TimePartitioning.) (.setType (-> type name .toUpperCase)))]
    (cond-> tp
      (int? expiration-ms)                   (.setExpirationMs expiration-ms)
      (string? field)                        (.setField (clean-name field))
      (boolean? require-partition-filter)    (.setRequirePartitionFilter require-partition-filter))))


(defn ^Clustering ->clustering
  [fields]
  (let [obj (Clustering.)]
    (.setFields ^Clustering obj (mapv clean-name fields))))

(defn get-bq-table-schema
  "Beware, uses bq util to get the schema!"
  [table-spec]
  (let [{:keys [exit out] :as return} (sh "bq" "--format=json" "show" (name table-spec))]
    (if (zero? exit)
      (-> (charred/read-json out :key-fn keyword) (:schema) (:fields))
      (throw (ex-info (str "Could not get bq table schema for table " table-spec)
                      {:table table-spec
                       :bq-return return})))))

(def write-disposition-enum
  {:append BigQueryIO$Write$WriteDisposition/WRITE_APPEND
   :empty BigQueryIO$Write$WriteDisposition/WRITE_EMPTY
   :truncate BigQueryIO$Write$WriteDisposition/WRITE_TRUNCATE})

(def create-disposition-enum
  {:if-needed BigQueryIO$Write$CreateDisposition/CREATE_IF_NEEDED
   :never BigQueryIO$Write$CreateDisposition/CREATE_NEVER})
(def retry-policy-enum
  {:never (InsertRetryPolicy/neverRetry)
   :always (InsertRetryPolicy/alwaysRetry)
   :retry-transient (InsertRetryPolicy/retryTransientErrors)})

(def write-method-enum
  {:default BigQueryIO$Write$Method/DEFAULT
   :load BigQueryIO$Write$Method/FILE_LOADS
   :streaming BigQueryIO$Write$Method/STREAMING_INSERTS})

(def schema-update-options-enum
  {:allow-field-addition BigQueryIO$Write$SchemaUpdateOption/ALLOW_FIELD_ADDITION
   :allow-field-relaxation BigQueryIO$Write$SchemaUpdateOption/ALLOW_FIELD_RELAXATION})

(def write-bq-table-schema
  (merge
   ds/base-schema
   {:schema {:docstr "Specifies bq schema."
             :action (fn [transform schema] (.withSchema transform (->schema schema)))}
    :json-schema {:docstr "Specifies bq schema in json"
                  :action (fn [transform json-schema] (let [sch (charred/read-json json-schema)
                                                            full-sch (if (get sch "fields")
                                                                       (ds/write-json-str sch)
                                                                       (ds/write-json-str {"fields" sch}))]
                                                        (.withJsonSchema transform full-sch)))}
    :table-description {:docstr "Specifies table description"
                        :action (fn [transform description] (.withTableDescription transform description))}
    :write-disposition {:docstr "Choose write disposition."
                        :enum write-disposition-enum
                        :action (ds/select-enum-option-fn
                                 :write-disposition
                                 write-disposition-enum
                                 (fn [transform enum] (.withWriteDisposition transform enum)))}
    :create-disposition {:docstr "Choose create disposition."
                         :enum create-disposition-enum
                         :action (ds/select-enum-option-fn
                                  :create-disposition
                                  create-disposition-enum
                                  (fn [transform enum] (.withCreateDisposition transform enum)))}
    :write-method {:docstr "Choose write method."
                   :enum write-method-enum
                   :action (ds/select-enum-option-fn
                            :write-method
                            write-method-enum
                            (fn [transform enum] (.withMethod transform enum)))}
    :without-validation {:docstr "Disables validation until runtime."
                         :action (fn [transform without-validation]
                                   (if without-validation
                                     (.withoutValidation transform)
                                     transform))}
    :with-optimized-writes {:docstr "Allows optimized writes feature, deactivated by default to maintain backward compatibility"
                            :action (fn [transform bool]
                                      (when (true? bool)
                                        (.optimizedWrites transform)))}
    :ignore-unknown-values {:docstr "Ignores fields which does not match the schema."
                            :action (fn [transform ignore-unknown-values]
                                      (if ignore-unknown-values
                                        (.ignoreUnknownValues transform)
                                        transform))}
    :schema-update-options {:docstr "Include schema update options. (pass in a list of options)"
                            :enum schema-update-options-enum
                            :action (ds/select-enum-option-fn-set
                                     :schema-update-options
                                     schema-update-options-enum
                                     (fn [transform enum] (.withSchemaUpdateOptions transform enum)))}
    :skip-invalid-rows {:docstr "Skips invalid rows. Only works with :streaming write method."
                        :action (fn [transform skip-invalid-rows]
                                  (if skip-invalid-rows
                                    (.skipInvalidRows transform)
                                    transform))}
    :retry-policy {:docstr "Specify retry policy for failed insert in streaming"
                   :action (ds/select-enum-option-fn
                            :retry-policy
                            retry-policy-enum
                            (fn [transform retrypolicy] (.withFailedInsertRetryPolicy transform retrypolicy)))}
    :time-partitioning {:docstr "Toggles write partitioning for the destination table"
                        :action (fn [transform opts]
                                  (.withTimePartitioning transform (->time-partitioning opts)))}
    :clustering {:docstr "Toggles clustering for the destination table"
                 :action (fn [transform opts]
                           (.withClustering transform (->clustering opts)))}}))

(defn custom-output-fn [cust-fn]
  (ds/sfn (fn [elt]
            (let [^String out (cust-fn elt)]
              (TableDestination. out nil)))))

(def format-fn (ds/sfn clj-nested->table-row))

(defn write-bq-table-raw
  ([to options ^PCollection pcoll]
   (let [opts (assoc options :label :write-bq-table-raw)]
     (ds/apply-transform pcoll (-> (BigQueryIO/write)
                                   (.to to)
                                   (.withFormatFunction format-fn))
                         write-bq-table-schema opts)))
  ([to pcoll] (write-bq-table-raw to {} pcoll)))

(defn- write-bq-table-clj-transform
  [to options]
  (let [safe-opts (dissoc options :name)]
    (ds/ptransform
     :write-bq-table-from-clj
     [^PCollection pcoll]
     (write-bq-table-raw to safe-opts pcoll))))

(defn write-bq-table
  ([to options ^PCollection pcoll]
   (let [opts (assoc options :label :write-bq-table)]
     (ds/apply-transform pcoll (write-bq-table-clj-transform to opts) ds/named-schema opts)))
  ([to pcoll] (write-bq-table to {} pcoll)))
