(ns toucan.connection
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.pprint :as pprint]
            [toucan
             [compile :as compile]
             [dispatch :as dispatch]
             [models :as models]]))

;;;                                                     Connection
;;; ==================================================================================================================

(defmulti spec
  "Return a JDBC connecton spec that should be to run queries. The spec is passed directly to `clojure.java.jdbc`; it
  can be anything that is accepted by it. In most cases, you'll want to use the same connection for all Models in your
  application; in this case, provide an implementation for `:default`:

    ;; set the default connection
    (defmethod connecton/spec :default [_]
      {:classname \"org.postgresql.Driver\"
       :subprotocol \"postgresql\"
       :subname     \"//localhost:5432/my_db\"
       :user        \"cam\"})

  You can implement this method for individual models for cases where your application connects to multiple databases.

  For most production uses, you'll probably want to use a connection pool (HikariCP and C3P0 are common choices)
  rather than establishing a new connection for each query; consider using something
  like [`metabase/connection-pool`](https://github.com/metabase/connection-pool):

    (require '[metabase.connection-pool :as pool])

    (def jdbc-spec
      {:classname \"org.postgresql.Driver\", ...})

    (def connection-pool
      (delay
       (pool/connection-pool-spec jdbc-spec)))

    (defmethod connecton/spec :default [_] @connection-pool)"
  {:arglists '([model])}
  dispatch/dispatch-value)

(when-not (get-method spec :default)
  (defmethod spec :default
    [_]
    ;; TODO - better exception message
    (throw (Exception. (str "Don't know how to get a DB connection. You can set the default connection by providing a"
                            " default implementation for `toucan.connection/spec`.")))))

(def ^:private ^:dynamic *transaction-connection*
  "Transaction connection to the application DB. Used internally by `transaction`."
  nil)

;; TODO - should we still have `*connection` if one wants to set it ?
(defn connection
  "The connection we are current Fetch a JDBC connection spec for passing to `clojure.java.jdbc`. Returns
  `*transaction-connection*`, if it is set (i.e., if we're inside a `transaction` -- this is bound automatically);
  otherwise calls `spec`."
  ([]
   (connection :default))

  ([model]
   (or *transaction-connection*
       (spec model))))

(defn do-in-transaction
  "Execute F inside a DB transaction. Prefer macro form `transaction` to using this directly."
  [f]
  (jdbc/with-db-transaction [conn (connection)]
    (binding [*transaction-connection* conn]
      (f))))

(defmacro transaction
  "Execute all queries within the body in a single transaction."
  {:arglists '([body] [options & body]), :style/indent 0}
  [& body]
  `(do-in-transaction (fn [] ~@body)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                   Debugging                                                    |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:dynamic *debug*
  ;; TODO - dox
  false)

(defmacro debug-println
  ;; TODO - dox
  [& args]
  `(when *debug*
     (println ~@args)))

(defmacro debug
  "Print the HoneySQL and SQL forms of any queries executed inside `body` to `stdout`. Intended for use during REPL
  development."
  {:style/indent 0}
  [& body]
  `(binding [*debug* true]
     ~@body))

(def ^:private ^:dynamic *call-count*
  "Atom used as a counter for DB calls when enabled. This number isn't *perfectly* accurate, only mostly; DB calls
  made directly to JDBC won't be logged."
  nil)

(defn -do-with-call-counting
  "Execute F with DB call counting enabled. F is passed a single argument, a function that can be used to retrieve the
  current call count. (It's probably more useful to use the macro form of this function, `with-call-counting`,
  instead.)"
  {:style/indent 0}
  [f]
  (binding [*call-count* (atom 0)]
    (f (partial deref *call-count*))))

(defn- inc-call-count!
  ;; TODO - dox
  []
  (when *call-count*
    (swap! *call-count* inc)))

(defmacro with-call-counting
  "Execute `body` and track the number of DB calls made inside it. `call-count-fn-binding` is bound to a zero-arity
  function that can be used to fetch the current DB call count.

     (db/with-call-counting [call-count] ...
       (call-count))"
  {:style/indent 1}
  [[call-count-fn-binding] & body]
  `(-do-with-call-counting (fn [~call-count-fn-binding] ~@body)))

(defmacro debug-count-calls
  "Print the number of DB calls executed inside `body` to `stdout`. Intended for use during REPL development."
  {:style/indent 0}
  [& body]
  `(with-call-counting [call-count#]
     (let [results# (do ~@body)]
       (println "DB Calls:" (call-count#))
       results#)))

;;;                                              Low-level JDBC functions
;;; ==================================================================================================================

(defn- maybe-compile [model query]
  (when (map? query)
    (debug-println "HoneySQL form:" (with-out-str (pprint/pprint query))))
  (let [sql-args (compile/maybe-compile-honeysql model query)]
    (debug-println "SQL & Args:" (with-out-str (pprint/pprint sql-args)))
    sql-args))

(defn- optional-model [args]
  (if (dispatch/dispatch-value (first args))
    args
    (cons nil args)))

(defmulti query*
  ;; TODO
  {:arglists '([model honeysql-form-or-sql-params jdbc-options])}
  dispatch/dispatch-value)

(when-not false #_(get-method query* :default)
  (defmethod query* :default
    [model honeysql-form-or-sql-params jdbc-options]
    (inc-call-count!)
    (jdbc/query (connection model) (maybe-compile model honeysql-form-or-sql-params) jdbc-options)))

(defn query
  ;; TODO - update dox
  "Compile `honeysql-from` and call `jdbc/query` against the application database. Options are passed along to
  `jdbc/query`."
  {:arglists '([model? honeysql-form-or-sql-params jdbc-options?])}
  [& args]
  (let [[model query jdbc-options] (optional-model args)]
    (query* model query jdbc-options)))

(defmulti reducible-query*
  ;; TODO
  {:arglists '([model honeysql-form-or-sql-params jdbc-options])}
  dispatch/dispatch-value)

(when-not (get-method reducible-query* :default)
  (defmethod reducible-query* :default
    [model honeysql-form-or-sql-params jdbc-options]
    (inc-call-count!)
    (jdbc/reducible-query (connection model) (maybe-compile model honeysql-form-or-sql-params) jdbc-options)))

(defn reducible-query
  ;; TODO - update dox
  "Compile `honeysql-from` and call `jdbc/reducible-query` against the application database. Options are passed along
  to `jdbc/reducible-query`. Note that the query won't actually be executed until it's reduced."
  {:arglists '([model? honeysql-form-or-sql-params jdbc-options?])}
  [& args]
  (let [[model query jdbc-options] (optional-model args)]
    (reducible-query* model query jdbc-options)))

(defn execute!
  "Compile `honeysql-form` and call `jdbc/execute!` against the application DB.
  `options` are passed directly to `jdbc/execute!` and can be things like `:multi?` (default `false`) or
  `:transaction?` (default `true`)."
  {:arglists '([model? honeysql-form-or-sql-params jdbc-options?])}
  [& args]
  (let [[model statement jdbc-options] (optional-model args)]
    (inc-call-count!)
    (jdbc/execute! (connection model) (maybe-compile model statement) jdbc-options)))

;; TODO - should these go here, or in `db.impl` ?

(defn insert!
  ;; TODO - `[honeysql-form-or-sql-params]` and `[model honeysql-form-or-sql-params opts]` arities
  [model honeysql-form-or-sql-params]
  (let [[sql & params] (maybe-compile model honeysql-form-or-sql-params)]
    (with-open [^java.sql.PreparedStatement prepared-statement (jdbc/prepare-statement
                                                                (jdbc/get-connection (connection model))
                                                                sql
                                                                {:return-keys (let [pk (models/primary-key model)]
                                                                                (if (sequential? pk) pk [pk]))})]
      (#'jdbc/dft-set-parameters prepared-statement params)
      (inc-call-count!)
      (when (pos? (.executeUpdate prepared-statement))
        (with-open [generated-keys-result-set (.getGeneratedKeys prepared-statement)]
          (vec (jdbc/result-set-seq generated-keys-result-set)))))))

(defn update!
  [model honeysql-form-or-sql-params]
  (let [sql-params (maybe-compile model honeysql-form-or-sql-params)]
    (let [[rows-affected] (execute! model sql-params)]
      (not (zero? rows-affected)))))

(defn delete!
  ;; TODO - `[honeysql-form-or-sql-params]` and `[model honeysql-form-or-sql-params opts]` arities
  [model honeysql-form-or-sql-params]
  (let [sql-params (maybe-compile model honeysql-form-or-sql-params)]
    (let [[rows-affected] (execute! model sql-params)]
      (not (zero? rows-affected)))))
