package com.authsignal.keycloak;

import com.authsignal.AuthsignalClient;
import com.authsignal.model.TrackAttributes;
import com.authsignal.model.TrackRequest;
import com.authsignal.model.TrackResponse;
import com.authsignal.model.UserActionState;
import com.authsignal.model.ValidateChallengeRequest;
import com.authsignal.model.ValidateChallengeResponse;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.credential.CredentialInput;

/** Authsignal Authenticator. */
public class AuthsignalAuthenticator implements Authenticator {
  private static final Logger logger = Logger.getLogger(AuthsignalAuthenticator.class.getName());

  public static final AuthsignalAuthenticator SINGLETON = new AuthsignalAuthenticator();

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    logger.info("authenticate method called");
    Response challenge = context.form()
        .setAttribute("message", "Please enter your token")
        .createForm("login.ftl");

    context.challenge(challenge);
    return;
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    logger.info("Action method called");


    AuthsignalClient authsignalClient = new AuthsignalClient(secretKey(context), baseUrl(context));

    MultivaluedMap<String, String> queryParams = context.getUriInfo().getQueryParameters();
    MultivaluedMap<String, String> formParams = context.getHttpRequest().getDecodedFormParameters();

    String username = formParams.getFirst("username");
    logger.info("username from form parameters: " + username);
    String token = formParams.getFirst("token");
    logger.info("Token from form parameters: " + token);
    
    if (token == null) {
      logger.info("token is null");
      token = queryParams.getFirst("token");
      logger.info("second token: " + token);
    }

    logger.info("token: " + token);

    if (token != null && !token.isEmpty()) {
      ValidateChallengeRequest request = new ValidateChallengeRequest();
      request.token = token;

      try {
        logger.info("calling validateChallenge method ");
        ValidateChallengeResponse response = authsignalClient.validateChallenge(request).get();
        logger.info("validateChallenge method called");
        logger.info("validateChallenge response: " + response);
        if (response.state == UserActionState.CHALLENGE_SUCCEEDED || response.state == UserActionState.ALLOW) {
            String userId = response.userId; // Update this based on your actual response structure
            logger.info("User ID retrieved from challenge: " + userId);

            // Retrieve the user by ID
            UserModel user = context.getSession().users().getUserById(context.getRealm(), userId);
            if (user == null) {
                logger.info("User not found in Keycloak for ID: " + userId);
                context.failure(AuthenticationFlowError.INVALID_USER);
                return;
            }

            // Set the user in the authentication context
            context.setUser(user);
            context.success();
        } else {
          context.failure(AuthenticationFlowError.ACCESS_DENIED);
        }
      } catch (Exception e) {
        e.printStackTrace();
        context.failure(AuthenticationFlowError.INTERNAL_ERROR);
      }
    } else {
      String password = formParams.getFirst("password"); // Assuming the password is passed in the form

      logger.info("username: " + username);
      logger.info("password: " + password);

      if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
          logger.warning("Username or password is missing");
          context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, context.form()
              .setError("Invalid username or password")
              .createForm("login.ftl"));
          return;
      }

      logger.info("Attempting to retrieve user by username: " + username);
      UserModel user = context.getSession().users().getUserByUsername(context.getRealm(), username);
      
      logger.info("user!: " + user);

      if (user == null) {
          logger.warning("User not found for username: " + username);
          context.failureChallenge(AuthenticationFlowError.INVALID_USER, context.form()
              .setError("Invalid username or password")
              .createForm("login.ftl"));
          return;
      }

      // boolean isValid = user.credentialManager()
      //     .isValid(context.getRealm(), UserCredentialModel.password(password));

      // if (!isValid) {
      //     logger.warning("Invalid password for username: " + username);
      //     context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, context.form()
      //         .setError("Invalid username or password")
      //         .createForm("login.ftl"));
      //     return;
      // }

    // Set the user in the context and proceed
    context.setUser(user);

    CredentialInput credentialInput = UserCredentialModel.password(password);

    boolean isValid = user.credentialManager().isValid(credentialInput);

    if (!isValid) {
        context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, context.form()
            .setError("Invalid username or password")
            .createForm("login.ftl"));
        return;
    }
    
    String sessionCode = context.generateAccessCode();

    logger.info("sessionCode: " + sessionCode);

    URI actionUri = context.getActionUrl(sessionCode);

    String redirectUrl =
        context.getHttpRequest().getUri().getBaseUri().toString().replaceAll("/+$", "")
            + "/realms/" + URLEncoder.encode(context.getRealm().getName(), StandardCharsets.UTF_8)
            + "/authsignal-authenticator/callback" + "?kc_client_id="
            + URLEncoder.encode(context.getAuthenticationSession().getClient().getClientId(),
                StandardCharsets.UTF_8)
            + "&kc_execution="
            + URLEncoder.encode(context.getExecution().getId(), StandardCharsets.UTF_8)
            + "&kc_tab_id="
            + URLEncoder.encode(context.getAuthenticationSession().getTabId(),
                StandardCharsets.UTF_8)
            + "&kc_session_code=" + URLEncoder.encode(sessionCode, StandardCharsets.UTF_8)
            + "&kc_action_url=" + URLEncoder.encode(actionUri.toString(), StandardCharsets.UTF_8);

    TrackRequest request = new TrackRequest();
    request.action = actionCode(context);

    request.attributes = new TrackAttributes();
    request.attributes.redirectUrl = redirectUrl;
    request.attributes.ipAddress = context.getConnection().getRemoteAddr();
    request.attributes.userAgent =
    context.getHttpRequest().getHttpHeaders().getHeaderString("User-Agent");
    request.userId = context.getUser().getId();
    request.attributes.username = context.getUser().getUsername();

    try {
      CompletableFuture<TrackResponse> responseFuture = authsignalClient.track(request);

      TrackResponse response = responseFuture.get();

      logger.info("response!: " + response.state);

      String url = response.url;

      Response responseRedirect =
          Response.status(Response.Status.FOUND).location(URI.create(url)).build();

      boolean isEnrolled = response.isEnrolled;

      // If the user is not enrolled (has no authenticators) and enrollment by default
      // is enabled,
      // display the challenge page to allow the user to enroll.
      if (enrolByDefault(context) && !isEnrolled) {
        if (response.state == UserActionState.BLOCK) {
          context.failure(AuthenticationFlowError.ACCESS_DENIED);
        }
        logger.info("responseRedirect!");
        context.challenge(responseRedirect);
      } else {
        if (response.state == UserActionState.CHALLENGE_REQUIRED) {
          context.challenge(responseRedirect);
        } else if (response.state == UserActionState.BLOCK) {
          context.failure(AuthenticationFlowError.ACCESS_DENIED);
        } else if (response.state == UserActionState.ALLOW) {
          logger.info("ALLOW!");
          context.success();
        } else {
          context.failure(AuthenticationFlowError.ACCESS_DENIED);
        }
      }

    } catch (Exception e) {
      e.printStackTrace();
      context.failure(AuthenticationFlowError.INTERNAL_ERROR);
    }
  }
    // No-op
}

  @Override
  public boolean requiresUser() {
    logger.info("requiresUser method called");
    return false;
  }

  @Override
  public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
    logger.info("configuredFor method called");
    return true;
  }

  @Override
  public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    logger.info("setRequiredActions method called");
    // No required actions
  }

  @Override
  public void close() {
    logger.info("close method called");
    // Cleanup if needed
  }

  private String generateConfigErrorMessage(String prefix) {
    return prefix + " Add provider details in your Keycloak admin portal.";
  }

  private String secretKey(AuthenticationFlowContext context) {
    AuthenticatorConfigModel config = context.getAuthenticatorConfig();
    if (config == null) {
      throw new IllegalStateException(
          generateConfigErrorMessage("Authsignal provider config is missing."));
    }
    Object secretKeyObj = config.getConfig().get(AuthsignalAuthenticatorFactory.PROP_SECRET_KEY);
    String tenantSecretKey = (secretKeyObj != null) ? secretKeyObj.toString() : null;

    if (tenantSecretKey == null || tenantSecretKey.isEmpty()) {
      throw new IllegalStateException(
          generateConfigErrorMessage("Authsignal Tenant Secret Key is not configured."));
    }
    return tenantSecretKey;
  }

  private String baseUrl(AuthenticationFlowContext context) {
    AuthenticatorConfigModel config = context.getAuthenticatorConfig();
    if (config == null) {
      throw new IllegalStateException(
          generateConfigErrorMessage("Authsignal provider config is missing."));
    }
    Object apiUrlObj =
        config.getConfig().get(AuthsignalAuthenticatorFactory.PROP_API_HOST_BASE_URL);
    String apiUrl = (apiUrlObj != null) ? apiUrlObj.toString() : null;

    if (apiUrl == null || apiUrl.isEmpty()) {
      throw new IllegalStateException(
          generateConfigErrorMessage("Authsignal API URL is not configured."));
    }
    return apiUrl;
  }

  private String actionCode(AuthenticationFlowContext context) {
    AuthenticatorConfigModel config = context.getAuthenticatorConfig();
    if (config == null) {
      return "sign-in";
    }

    Object actionCodeObj = config.getConfig().get(AuthsignalAuthenticatorFactory.PROP_ACTION_CODE);
    String actionCode = (actionCodeObj != null) ? actionCodeObj.toString() : null;

    if (actionCode == null) {
      return "sign-in";
    }
    return actionCode;
  }

  private Boolean enrolByDefault(AuthenticationFlowContext context) {
    AuthenticatorConfigModel config = context.getAuthenticatorConfig();
    if (config == null) {
      return true;
    }
    Boolean enrolByDefault = Boolean
        .valueOf(config.getConfig().get(AuthsignalAuthenticatorFactory.PROP_ENROL_BY_DEFAULT));
    return enrolByDefault;
  }
}
