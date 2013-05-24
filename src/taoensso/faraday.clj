(ns taoensso.faraday
  "Clojure DynamoDB client. A fork of Rotary by James Reeves,
  Ref. https://github.com/weavejester/rotary"
  {:author "Peter Taoussanis"}
  (:require [clojure.string         :as str]
            [taoensso.timbre        :as timbre]
            [taoensso.nippy         :as nippy]
            [taoensso.faraday.utils :as utils])
  (:import  [com.amazonaws.services.dynamodbv2.model
             AttributeValue
             AttributeDefinition
             BatchGetItemRequest
             BatchGetItemResult
             ;; BatchResponse
             BatchWriteItemRequest
             BatchWriteItemResult
             ;; BatchWriteResponse
             Condition
             CreateTableRequest
             UpdateTableRequest
             DescribeTableRequest
             DescribeTableResult
             DeleteTableRequest
             DeleteItemRequest
             DeleteRequest
             ExpectedAttributeValue
             GetItemRequest
             GetItemResult
             ;; Key
             ;; KeySchema
             KeySchemaElement
             KeysAndAttributes
             ProvisionedThroughput
             ProvisionedThroughputDescription
             PutItemRequest
             PutRequest
             QueryRequest
             ResourceNotFoundException
             ScanRequest
             WriteRequest]
            com.amazonaws.ClientConfiguration
            com.amazonaws.auth.BasicAWSCredentials
            com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
            java.nio.ByteBuffer))

;;;; TODO Rotary PRs
;; * Feature/can update dynamo items (mrgordon)
;; * More flexible key support and additional options from SDK (mrgordon)
;; * Added support for binary values (mantree)
;; * Upgrade to use dynamodb v2 api (edpaget)
;; * Local secondary indexes (edpaget)
;; * Non-PR forks.

;;;; TODO
;; * Code walk-through.
;; * Reflection warnings.
;; * Update SDK dep.
;; * Update to v2 API.
;; * Go through Rotary PRs.
;; * Bin support + serialization.
;; * Tests!
;; * Docs.
;; * Bench.

;;;; API - core objects

(defn- db-client*
  "Returns a new AmazonDynamoDBClient instance for the supplied IAM credentials."
  [{:keys [access-key secret-key endpoint proxy-host proxy-port] :as creds}]
  (let [aws-creds     (BasicAWSCredentials. access-key secret-key)
        client-config (ClientConfiguration.)]
    (when proxy-host (.setProxyHost client-config proxy-host))
    (when proxy-port (.setProxyPort client-config proxy-port))
    (let [client (AmazonDynamoDBClient. aws-creds client-config)]
      (when endpoint (.setEndpoint client endpoint))
      client)))

(def db-client (memoize db-client*))

(defn- key-schema-element "Returns a new KeySchemaElement object."
  ;; [{key-name :name key-type :type}]
  [key-name key-type]
  (doto (KeySchemaElement.)
    (.setAttributeName (str key-name))
    (.setKeyType (str/upper-case (name key-type)))))

(defn- key-schema "Returns a new KeySchema object."
  [hash-key & [range-key]]
  (let [schema (key-schema-element (:name hash-key) :hash)]
    (if range-key
      (conj schema (key-schema-element (:name range-key) :range))
      schema)))

(defn- provisioned-throughput "Returns a new ProvisionedThroughput object."
  [{read-units :read write-units :write}]
  (doto (ProvisionedThroughput.)
    (.setReadCapacityUnits  (long read-units))
    (.setWriteCapacityUnits (long write-units))))

(defn- attribute-definition "Returns a new AttributeDefinition objects."
  [{key-name :name key-type :type :as def}] ; TODO Why not [key-name key-type]?
  (doto (AttributeDefinition.)
    (.setAttributeName key-name)
    (.setAttributeType (str/upper-case (name key-type)))))

(defn- attribute-definitions "Returns a new vector of AttributeDefinition objects."
  [defs] (mapv attribute-definition defs))

;;;; Coercion - values

;; TODO Nippy support (will require special marker bin-wrapping), incl.
;; Serialized (boxed) type (should allow empty strings & ANY type of set)
;; Maybe require special wrapper types for writing bins/serialized

(defn- str->num [^String s] (if (.contains s ".") (Double. s) (Long. s)))
(defn- bb->ba   [^ByteBuffer bb] (.array bb))

(defn- db-val->clj-val
  "Returns the Clojure value of given AttributeValue object."
  [^AttributeValue x]
  (or (.getS x)
      (some->> (.getN  x) str->num)
      (some->> (.getB  x) bb->ba)
      (some->> (.getSS x) (into #{}))
      (some->> (.getNS x) (map str->num) (into #{}))
      (some->> (.getBS x) (map bb->ba)   (into #{}))))

(defn- set-of [pred s] (and (set? s) (seq s) (every? pred s)))
(def ^:private ^:const ba-class (Class/forName "[B"))
(defn- ba? [x] (instance? ba-class x))
(defn- ba-buffer [^bytes ba] (ByteBuffer/wrap ba))
(defn- simple-num? [x] (or (instance? Long    x)
                           (instance? Double  x)
                           (instance? Integer x)
                           (instance? Float   x)))

(defn- clj-val->db-val
  "Returns an AttributeValue object for given Clojure value."
  [x]
  (cond
   (string? x)          (if (.isEmpty ^String x)
                          (throw (Exception. "\"\" is not a legal DynamoDB value!"))
                          (doto (AttributeValue.) (.setS x)))
   (simple-num? x)        (doto (AttributeValue.) (.setN (str x)))
   (ba? x)                (doto (AttributeValue.) (.setB (ba-buffer x)))
   (set-of string? x)     (doto (AttributeValue.) (.setSS x))
   (set-of simple-num? x) (doto (AttributeValue.) (.setNS (map str x)))
   (set-of ba? x)         (doto (AttributeValue.) (.setBS (map ba-buffer x)))
   (set? x) (throw (Exception. "Sets must be non-empty & contain values of the same type!"))
   :else    (throw (Exception. (str "Unknown value type: " (type x))))))

(comment (map clj-val->db-val ["foo" 10 3.14 (.getBytes "foo")
                               #{"a" "b" "c"} #{1 2 3.14} #{(.getBytes "foo")}]))

;;;; Coercion - objects

(defn- db-map->clj-map [x] (when x (utils/fmap db-val->clj-val (into {} x))))

(defprotocol AsMap (as-map [x]))

(extend-protocol AsMap

  java.util.ArrayList (as-map [alist] (into [] (map as-map alist)))
  java.util.HashMap   (as-map [hmap] (utils/fmap as-map (into {} hmap)))
  AttributeValue      (as-map [aval] (db-val->clj-val aval))
  KeySchemaElement    (as-map [element] {:name (.getAttributeName element)})

  ProvisionedThroughputDescription
  (as-map [throughput]
    {:read  (.getReadCapacityUnits throughput)
     :write (.getWriteCapacityUnits throughput)
     :last-decrease (.getLastDecreaseDateTime throughput)
     :last-increase (.getLastIncreaseDateTime throughput)})

  DescribeTableResult
  (as-map [result]
    (let [table (.getTable result)]
      {:name          (.getTableName table)
       :creation-date (.getCreationDateTime table)
       :item-count    (.getItemCount table)
       :key-schema    (as-map (.getKeySchema table))
       :throughput    (as-map (.getProvisionedThroughput table))
       :status        (-> (.getTableStatus table)
                          (str/lower-case)
                          (keyword))}))

  BatchWriteItemResult
  (as-map [result]
    {:unprocessed-items (into {} (.getUnprocessedItems result))})

  KeysAndAttributes
  (as-map [result]
    (merge
     (when-let [a (.getAttributesToGet result)] {:attrs (into [] a)})
     (when-let [c (.getConsistentRead result)]  {:consistent c})
     (when-let [k (.getKeys result)]            {:keys (utils/fmap as-map (into [] k))})))

  BatchGetItemResult
  (as-map [result]
    {:responses        (utils/fmap as-map (into {} (.getResponses result)))
     :unprocessed-keys (utils/fmap as-map (into {} (.getUnprocessedKeys result)))})

  GetItemResult (as-map [result] (db-map->clj-map (.getItem result)))

  nil (as-map [_] nil))

;;;; API - tables

(defn create-table
  "Create a table in DynamoDB with the given map of properties. The properties
  available are:
    :name       - the name of the table (required)
    :hash-key   - a map that defines the hash key name and type (required)
    :range-key  - a map that defines the range key name and type (optional)
    :throughput - a map that defines the read and write throughput (required)

  The hash-key and range-key definitions are maps with the following keys:
    :name - the name of the key
    :type - the type of the key (:s, :n, :ss, :ns)

  Where :s is a string type, :n is a number type, and :ss and :ns are sets of
  strings and number respectively.

  The throughput is a map with two keys:
    :read  - the provisioned number of reads per second
    :write - the provisioned number of writes per second

  ;; TODO +
  The indexes vector is a vector of maps with keys:
    :name           - the name of the Local Secondary Index (required)
    :range-key      - a map that defines the range key name and type (required)
    :projection     - keyword that defines the projection may be:
      :all, :keys_only, :include (optional - default is :keys-only)
    :included-attrs - a vector of attribute names when :projection is :include
                      (optional)"
  [creds {:keys [name hash-key range-key throughput]}] ;; TODO +indexes
  (.createTable
   (db-client creds)
   (let [defined-attrs (->> (conj [] hash-key range-key)
                              ;; (concat (map #(:range-key %) indexes))
                            (filter identity))]
     (doto (CreateTableRequest.)
       (.setTableName (str name))
       (.setKeySchema (key-schema hash-key range-key))
       (.setAttributeDefinitions (attribute-definitions defined-attrs))
       (.setProvisionedThroughput (provisioned-throughput throughput))
       ;; (.setLocalSecondaryIndexes (local-indexes hash-key indexes))
       ))))

(defn update-table
  "Update a table in DynamoDB with the given name. Only the throughput may be
  updated. The throughput values can be increased by no more than a factor of
  two over the current values (e.g. if your read throughput was 20, you could
  only set it from 1 to 40). See create-table."
  [creds {:keys [name throughput]}]
  (.updateTable
   (db-client creds)
   (doto (UpdateTableRequest.)
     (.setTableName (str name))
     (.setProvisionedThroughput (provisioned-throughput throughput)))))

(defn describe-table
  "Returns a map describing the table in DynamoDB with the given name, or nil
  if the table doesn't exist."
  [creds name]
  (try (as-map
        (.describeTable
         (db-client creds)
         (doto (DescribeTableRequest.)
           (.setTableName name))))
       (catch ResourceNotFoundException _)))

(defn ensure-table
  "Creates the table if it does not already exist."
  [creds {name :name :as properties}]
  (if-not (describe-table creds name)
    (create-table creds properties)))

(defn delete-table
  "Delete a table in DynamoDB with the given name."
  [creds name]
  (.deleteTable
   (db-client creds)
   (DeleteTableRequest. name)))

(defn list-tables
  "Return a list of tables in DynamoDB."
  [creds]
  (-> (db-client creds)
      .listTables
      .getTableNames
      seq))

;;;; API - items

(defn- set-expected-value! ; TODO PR
  "Makes a Put request conditional by setting its expected value"
  [req expected]
  (when expected
    (.setExpected req (utils/fmap #(if (instance? Boolean %)
                                     (ExpectedAttributeValue. %)
                                     (ExpectedAttributeValue. (clj-val->db-val %)))
                                  expected))))

(defn put-item
  ;;"Add an item (a Clojure map) to a DynamoDB table."
  "Add an item (a Clojure map) to a DynamoDB table. Optionally accepts
  a map after :expected that declares expected values of the item in DynamoDB
  or the Boolean false if they are not expected to be present. If the condition
  is not met, it fails with a ConditionalCheckFailedException.

  Example: (would not add item if it already exists))
    (put-item credentials \"table\"
      {\"id\" 1 \"attr\" \"foo\"}
      :expected {\"id\" false})"
  [creds table item & {:keys [expected]}] ; TODO PR
  ;; [creds table item]
  (.putItem
   (db-client creds)
   (doto (PutItemRequest.)
     (.setTableName table)
     (.setItem
      (into {}
            (for [[k v] item]
              [(name k) (clj-val->db-val v)])))
     (set-expected-value! expected))))

;; (defn- item-key
;;   "Create a Key object from a value."
;;   [{:keys [hash-key range-key]}]
;;   (let [key (Key.)]
;;     (when hash-key
;;       (.setHashKeyElement key (clj-val->db-val hash-key)))
;;     (when range-key
;;       (.setRangeKeyElement key (clj-val->db-val range-key)))
;;     key))

(defn- item-key "Returns a new Key map." ; TODO Key?
  [key] ; TODO key?
  (into {} (map (fn [[k v]] {(name k) (clj-val->db-val v)}) key)))

(defn get-item
  "Retrieve an item from a DynamoDB table by its hash key."
  ;; TODO hash-key should be specified as a map of {:attr-name value}. Huh??
  [creds table hash-key]
  (as-map
   (.getItem
    (db-client creds)
    (doto (GetItemRequest.)
      (.setTableName table)
      (.setKey (item-key hash-key))))))

(defn delete-item
  "Delete an item from a DynamoDB table by its hash key."
  [creds table hash-key]
  (.deleteItem
   (db-client creds)
   (DeleteItemRequest. table (item-key hash-key))))

;;;; API - batch ops

;; (defn- batch-item-keys [request-or-keys]
;;   (for [key (:keys request-or-keys request-or-keys)]
;;     (if (map? key)
;;       (item-key key)
;;       (item-key {:hash-key key}))))

(defn- batch-item-keys [request-or-keys]
  (for [key (:keys request-or-keys request-or-keys)]
    (item-key {(:key-name request-or-keys) key})))

(defn- keys-and-attrs [{:keys [attrs consistent] :as request}]
  (let [kaa (KeysAndAttributes.)]
    (.setKeys kaa (batch-item-keys request))
    (when attrs
      (.setAttributesToGet kaa attrs))
    (when consistent
      (.setConsistentRead kaa consistent))
    kaa))

(defn- batch-request-items [requests]
  (into {}
    (for [[k v] requests]
      [(name k) (keys-and-attrs v)])))

(defn batch-get-item
  "Retrieve a batch of items in a single request. DynamoDB limits
   apply - 100 items and 1MB total size limit. Requested items
   which were elided by Amazon are available in the returned map
   key :unprocessed-keys.

  Example:
  (batch-get-item cred
    {:users {:key-name \"names\"
             :keys [\"alice\" \"bob\"]}
     :posts {:key-name \"id\"
             :keys [1 2 3]
             :attrs [\"timestamp\" \"subject\"]
             :consistent true}})"
  [creds requests]
  (as-map
    (.batchGetItem
      (db-client creds)
      (doto (BatchGetItemRequest.)
        (.setRequestItems (batch-request-items requests))))))

(defn- delete-request [item]
  (doto (DeleteRequest.)
    (.setKey (item-key item))))

(defn- put-request [item]
  (doto (PutRequest.)
    (.setItem
      (into {}
        (for [[id field] item]
          {(name id) (clj-val->db-val field)})))))

(defn- write-request [verb item]
  (let [wr (WriteRequest.)]
    (case verb
      :delete (.setDeleteRequest wr (delete-request item))
      :put    (.setPutRequest wr (put-request item)))
    wr))

(defn batch-write-item
  "Execute a batch of Puts and/or Deletes in a single request.
   DynamoDB limits apply - 25 items max. No transaction
   guarantees are provided, nor conditional puts.

   Example:
   (batch-write-item creds
     [:put :users {:user-id 1 :username \"sally\"}]
     [:put :users {:user-id 2 :username \"jane\"}]
     [:delete :users {:hash-key 3}])"
  [creds & requests]
  (as-map
    (.batchWriteItem
      (db-client creds)
      (doto (BatchWriteItemRequest.)
        (.setRequestItems
         (utils/fmap
          (fn [reqs]
            (reduce
             #(conj %1 (write-request (first %2) (last %2)))
             []
             (partition 3 (flatten reqs))))
          (group-by #(name (second %)) requests)))))))

;;;; API - queries & scans

(defn- result-map [results]
  {:items    (map db-map->clj-map (.getItems results))
   :count    (.getCount results)
   :last-key (as-map (.getLastEvaluatedKey results))})

(defn- scan-request
  "Create a ScanRequest object."
  [table {:keys [limit count after]}]
  (let [sr (ScanRequest. table)]
    (when limit
      (.setLimit sr (int limit)))
    (when count
      (.setCount sr count))
    (when after
      (.setExclusiveStartKey sr (item-key after)))
    sr))

(defn scan
  "Return the items in a DynamoDB table. Takes the following options:
    :limit - the maximum number of items to return
    :after - only return results after this key

  The items are returned as a map with the following keys:
    :items    - the list of items returned
    :count    - the count of items matching the query
    :last-key - the last evaluated key (useful for paging) "
  [creds table & [options]]
  (result-map
   (.scan
    (db-client creds)
    (scan-request table options))))

(defn- set-hash-condition
  "Create a map of specifying the hash-key condition for query"
  [hash-key]
  (utils/fmap #(doto (Condition.)
                 (.setComparisonOperator "EQ")
                 (.setAttributeValueList [(clj-val->db-val %)]))
              hash-key))

;; (defn- set-range-condition
;;   "Add the range key condition to a QueryRequest object"
;;   [query-request operator & [range-key range-end]]
;;   (let [attribute-list (->> [range-key range-end] (remove nil?) (map clj-val->db-val))]
;;     (.setRangeKeyCondition query-request
;;                            (doto (Condition.)
;;                              (.withComparisonOperator operator)
;;                              (.withAttributeValueList attribute-list)))))

(defn- set-range-condition
  "Add the range key condition to a QueryRequest object"
  [range-key operator & [range-value range-end]]
  (let [attribute-list (->> [range-value range-end] (filter identity) (map clj-val->db-val))]
    {range-key (doto (Condition.)
                 (.setComparisonOperator operator)
                 (.setAttributeValueList attribute-list))}))

(defn- normalize-operator
  "Maps Clojure operators to DynamoDB operators"
  [operator]
  (let [operator-map {:> "GT" :>= "GE" :< "LT" :<= "LE" := "EQ"}
        op (->> operator name str/upper-case)]
    (operator-map (keyword op) op)))

;; (defn- query-request
;;   "Create a QueryRequest object."
;;   [table hash-key range-clause {:keys [order limit after count consistent attrs]}]
;;   (let [qr (QueryRequest. table (clj-val->db-val hash-key))
;;         [operator range-key range-end] range-clause]
;;     (when operator
;;       (set-range-condition qr (normalize-operator operator) range-key range-end))
;;     (when attrs
;;       (.setAttributesToGet qr (map name attrs)))
;;     (when order
;;       (.setScanIndexForward qr (not= order :desc)))
;;     (when limit
;;       (.setLimit qr (int limit)))
;;     (when count
;;       (.setCount qr count))
;;     (when consistent
;;       (.setConsistentRead qr consistent))
;;     (when after
;;       (.setExclusiveStartKey qr (item-key after)))
;;     qr))

(defn- query-request
  "Create a QueryRequest object."
  [table hash-key range-clause {:keys [order limit after count consistent attrs]}] ;; TODO +index
  (let [qr (QueryRequest.)
        hash-clause (set-hash-condition hash-key)
        [range-key operator range-value range-end] range-clause
        query-conditions (if-not operator
                           hash-clause
                           (merge hash-clause (set-range-condition range-key
                                                                   (normalize-operator operator)
                                                                   range-value
                                                                   range-end)))]
    (.setTableName qr table)
    (.setKeyConditions qr query-conditions)
    (when attrs      (.setAttributesToGet qr (map name attrs)))
    (when order      (.setScanIndexForward qr (not= order :desc)))
    (when limit      (.setLimit qr (int limit)))
    (when count      (.setCount qr count))
    (when consistent (.setConsistentRead qr consistent))
    (when after      (.setExclusiveStartKey qr (item-key after)))
    ;; (when index      (.setIndexName qr index)) ; TODO
    qr))

(defn- extract-range [[range options]]
  (if (and (map? range) (not options))
    [nil range]
    [range options]))

;; (defn query
;;   "Return the items in a DynamoDB table matching the supplied hash key.
;;   Can specify a range clause if the table has a range-key ie. `(>= 234)
;;   Takes the following options:
;;     :order - may be :asc or :desc (defaults to :asc)
;;     :attrs - limit the values returned to the following attribute names
;;     :limit - the maximum number of items to return
;;     :after - only return results after this key
;;     :consistent - return a consistent read if logical true

;;   The items are returned as a map with the following keys:
;;     :items    - the list of items returned
;;     :count    - the count of items matching the query
;;     :last-key - the last evaluated key (useful for paging)"
;;   [creds table hash-key & range-and-options]
;;   (let [[range options] (extract-range range-and-options)]
;;     (result-map
;;      (.query
;;       (db-client creds)
;;       (query-request table hash-key range options)))))

(defn query
  "Return the items in a DynamoDB table matching the supplied hash key,
  defined in the form {\"hash-attr\" hash-value}.
  Can specify a range clause if the table has a range-key ie. `(\"range-attr\" >= 234)
  Takes the following options:
    :order - may be :asc or :desc (defaults to :asc)
    :attrs - limit the values returned to the following attribute names
    :limit - the maximum number of items to return
    :after - only return results after this key
    :consistent - return a consistent read if logical true
;;    :index - the secondary index to query ; TODO

  The items are returned as a map with the following keys:
    :items - the list of items returned
    :count - the count of items matching the query
    :last-key - the last evaluated key (useful for paging)"
  [creds table hash-key & range-and-options]
  (let [[range options] (extract-range range-and-options)]
    (result-map
     (.query
      (db-client creds)
      (query-request table hash-key range options)))))