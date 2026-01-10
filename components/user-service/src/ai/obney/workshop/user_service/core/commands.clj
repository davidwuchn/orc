(ns ai.obney.workshop.user-service.core.commands
  "The core commands namespace in a grain service component implements
   the command handlers and defines the command registry. Command functions
   take a context that includes any necessary dependencies, to be injected
   in the base for the service. Usually a command-request-handler or another 
   type of adapter will call the command processor, which will access the command 
   registry for the entire application in the context. Commands either return a cognitect 
   anomaly or a map that optionally has a :command-result/events key containing a sequence of 
   valid events per the event-store event schema and optionally :command/result which is some 
   data that is meant to be returned to the caller, see command-request-handler for example."
  (:require [ai.obney.workshop.user-service.core.read-models :as rm]
            [ai.obney.workshop.jwt.interface :as jwt]
            [ai.obney.workshop.email.interface :as email]
            [ai.obney.grain.event-store-v2.interface :refer [->event] :as es]
            [ai.obney.grain.command-processor.interface :refer [defcommand]]
            [buddy.hashers :as hashers]
            [cognitect.anomalies :as anom]))

(defn get-unique-email-addresses
  [event-store]
  (->> (es/read event-store {:types rm/user-event-types})
       (rm/apply-events)
       vals
       (map :user/email-address)
       (into #{})))

(defcommand :user sign-up
  "Register a new user account."
  [{{:keys [email-address password]} :command
    :keys [event-store]}]
  (let [existing-email-addresses (get-unique-email-addresses event-store)]
    (if (contains? existing-email-addresses email-address)
      {::anom/category ::anom/conflict
       ::anom/message "Email already registered to existing account!"}
      (let [user-id (random-uuid)]
        {:command-result/events [(->event {:type :user/signed-up
                                           :tags #{[:user user-id]}
                                           :body {:user-id user-id
                                                  :email-address email-address
                                                  :password (hashers/derive password)}})]}))))

(defcommand :user login
  "Authenticate a user and return a JWT."
  [{{:keys [email-address password]} :command
    :keys [event-store jwt-secret]}]
  (let [existing-email-addresses (get-unique-email-addresses event-store)]
    (if (not (contains? existing-email-addresses email-address))
      {::anom/category ::anom/conflict
       ::anom/message "Invalid Credentials!"}
      (let [{user-id :user/id
             existing-password :user/password
             :as user}
            (->> (es/read event-store {:types rm/user-event-types})
                 (rm/apply-events)
                 vals
                 (filter #(= email-address (:user/email-address %)))
                 (first))]
        (cond
          (not (:valid (hashers/verify password existing-password)))
          {::anom/category ::anom/conflict
           ::anom/message "Invalid Credentials!"}

          (not (:user/email-verified user))
          {::anom/category ::anom/forbidden
           ::anom/message "Please verify your email address"}

          :else
          {:command-result/events
           [(->event {:type :user/logged-in
                      :tags #{[:user user-id]}
                      :body {:user-id user-id
                             :email-address email-address}})]
           :jwt (jwt/sign
                 {:payload
                  {:user-id (str user-id)
                   :email email-address}
                  :secret jwt-secret
                  :does-not-expire true})})))))




(defcommand :user send-welcome-email
  "Send a welcome email to a new user."
  [{{:keys [user-id email-address]} :command}]
  {:command-result/events [(->event {:type :user/welcome-email-sent
                                     :tags #{[:user user-id]}
                                     :body {:user-id user-id
                                            :email-address email-address}})]})


(defcommand :user send-verification-link
  "Send an email verification link to a user."
  [{:keys [jwt-secret email-client email-from app-base]
    {:keys [user-id email-address]} :command}]
  (let [jwt
        (jwt/sign
         {:payload {:user-id user-id
                    :email-address email-address}
          :secret jwt-secret
          :does-not-expire true})]
    (email/send
     email-client
     {:from email-from
      :to [email-address]
      :subject "Please verify your email address"
      :body-html (format "<html><body>
                            <p>Click the link to verify your email address:
                          <a href=\"%s/auth/verify-email?jwt=%s\">Verify Email</a></p>
                          </body></html>"
                         app-base
                         jwt)})
    {:command-result/events [(->event {:type :user/verification-link-sent
                                       :tags #{[:user user-id]}
                                       :body {:email-address email-address
                                              :jwt jwt}})]}))

(defcommand :user verify-email
  "Verify a user's email address using a JWT."
  [{:keys [jwt-secret]
    {:keys [jwt]} :command}]
  (try
    (let [jwt-unsigned (jwt/unsign {:token jwt :secret jwt-secret})]
      {:command-result/events [(->event {:type :user/email-verified
                                         :tags #{[:user (java.util.UUID/fromString (:user-id jwt-unsigned))]}
                                         :body {:user-id (java.util.UUID/fromString (:user-id jwt-unsigned))
                                                :email-address (:email-address jwt-unsigned)}})]})
    (catch Exception _e
      {::anom/category ::anom/conflict
       ::anom/message "JWT is expired!"})))


(defcommand :user request-password-reset
  "Send a password reset link to a user."
  [{{:keys [email-address]} :command
    :keys [event-store jwt-secret email-client email-from app-base]}]
  (let [{user-id :user/id :as _user} (->> (es/read event-store {:types rm/user-event-types})
                                          (rm/apply-events)
                                          vals
                                          (filter #(= email-address (:user/email-address %)))
                                          (first))
        jwt
        (jwt/sign
         {:payload
          {:user-id user-id
           :email-address email-address}
          :expire-in [5 :minutes]
          :secret jwt-secret})]
    (if-not user-id
      {::anom/category ::anom/conflict
       ::anom/message "User does not exist!"}
      (do
        (email/send
         email-client
         {:from email-from
          :to [email-address]
          :subject "Password Reset Request"
          :body-html (format "<html><body>
                                    <p>Click the link to reset your password:
                                  <a href=\"%s/auth/reset-password?jwt=%s\">Reset Password</a></p>
                                  </body></html>"
                             app-base
                             jwt)})
        {:command-result/events [(->event {:type :user/password-reset-link-sent
                                           :tags #{[:user user-id]}
                                           :body {:email-address email-address
                                                  :jwt jwt}})]}))))

(defcommand :user reset-password
  "Reset a user's password using a JWT."
  [{{:keys [password jwt]} :command
    :keys [jwt-secret]}]
  (try
    (let [user-id (java.util.UUID/fromString (get (jwt/unsign {:token jwt :secret jwt-secret}) :user-id))]
      {:command-result/events [(->event {:type :user/password-reset
                                         :tags #{[:user user-id]}
                                         :body {:user-id user-id
                                                :jwt jwt
                                                :password (hashers/derive password)}})]})
    (catch Exception _e
      {::anom/category ::anom/conflict
       ::anom/message "JWT is expired!"})))

(defcommand :user send-password-reset-notification
  "Send a notification that a password was reset."
  [{{:keys [user-id]} :command}]
  {:command-result/events [(->event {:type :user/password-reset-notification-sent
                                     :tags #{[:user user-id]}
                                     :body {:user-id user-id}})]})

(defcommand :user check-session
  "Check if the user has a valid session."
  [context]
  (if (:auth-claims context)
    {}
    {::anom/category ::anom/forbidden}))

(defcommand :user logout
  "Log out the current user."
  [_context]
  {})
