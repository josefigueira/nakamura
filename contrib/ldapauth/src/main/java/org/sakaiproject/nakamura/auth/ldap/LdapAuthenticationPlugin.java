/*
 * Licensed to the Sakai Foundation (SF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.sakaiproject.nakamura.auth.ldap;

import com.novell.ldap.LDAPAttribute;
import com.novell.ldap.LDAPConnection;
import com.novell.ldap.LDAPEntry;
import com.novell.ldap.LDAPException;
import com.novell.ldap.LDAPSearchResults;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.commons.osgi.OsgiUtil;
import org.apache.sling.jackrabbit.usermanager.impl.resource.AuthorizableResourceProvider;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.base.util.AccessControlUtil;
import org.apache.sling.jcr.jackrabbit.server.security.AuthenticationPlugin;
import org.apache.sling.servlets.post.Modification;
import org.sakaiproject.nakamura.api.ldap.LdapConnectionManager;
import org.sakaiproject.nakamura.api.ldap.LdapUtil;
import org.sakaiproject.nakamura.api.user.AuthorizablePostProcessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import javax.jcr.Credentials;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.ValueFactory;

/**
 * Authentication plugin for verifying a user against an LDAP instance.
 */
@Component(metatype = true)
@Service(value = LdapAuthenticationPlugin.class)
public class LdapAuthenticationPlugin implements AuthenticationPlugin {

  private static final Logger log = LoggerFactory
      .getLogger(LdapAuthenticationPlugin.class);

  private static final String UTF8 = "UTF-8";

  @Property(value = "o=sakai")
  static final String LDAP_BASE_DN = "sakai.auth.ldap.baseDn";
  private String baseDn;

  @Property(value = "uid={}")
  static final String USER_FILTER = "sakai.auth.ldap.filter.user";
  private String userFilter;

  /**
   * Filter applied to make sure user has the required authorization (ie. attributes).
   */
  @Property(value = "(&(allowSakai=true))")
  static final String AUTHZ_FILTER = "sakai.auth.ldap.filter.authz";
  private String authzFilter;

  public static final boolean CREATE_ACCOUNT_DEFAULT = true;
  @Property(boolValue = CREATE_ACCOUNT_DEFAULT)
  static final String CREATE_ACCOUNT = "sakai.auth.ldap.account.create";
  private boolean createAccount;

  public static final boolean DECORATE_USER_DEFAULT = true;
  @Property(boolValue = DECORATE_USER_DEFAULT)
  static final String DECORATE_USER = "sakai.auth.ldap.user.decorate";
  private boolean decorateUser;

  public static final String FIRST_NAME_PROP_DEFAULT = "firstName";
  @Property(value = FIRST_NAME_PROP_DEFAULT)
  static final String FIRST_NAME_PROP = "sakai.auth.ldap.prop.firstName";
  private String firstNameProp;

  public static final String LAST_NAME_PROP_DEFAULT = "lastName";
  @Property(value = LAST_NAME_PROP_DEFAULT)
  static final String LAST_NAME_PROP = "sakai.auth.ldap.prop.lastName";
  private String lastNameProp;

  public static final String EMAIL_PROP_DEFAULT = "email";
  @Property(value = EMAIL_PROP_DEFAULT)
  static final String EMAIL_PROP = "sakai.auth.ldap.prop.email";
  private String emailProp;

  @Reference
  private LdapConnectionManager connMgr;

  @Reference
  private AuthorizablePostProcessService authzPostProcessorService;

  @Reference
  private SlingRepository slingRepository;

  public LdapAuthenticationPlugin() {
  }

  LdapAuthenticationPlugin(LdapConnectionManager connMgr) {
    this.connMgr = connMgr;
  }

  @Activate
  protected void activate(Map<?, ?> props) {
    init(props);
  }

  @Modified
  protected void modified(Map<?, ?> props) {
    init(props);
  }

  private void init(Map<?, ?> props) {
    baseDn = OsgiUtil.toString(props.get(LDAP_BASE_DN), "");
    userFilter = OsgiUtil.toString(props.get(USER_FILTER), "");
    authzFilter = OsgiUtil.toString(props.get(AUTHZ_FILTER), "");
    createAccount = OsgiUtil.toBoolean(props.get(DECORATE_USER), DECORATE_USER_DEFAULT);
    decorateUser = OsgiUtil.toBoolean(props.get(DECORATE_USER), DECORATE_USER_DEFAULT);
    firstNameProp = OsgiUtil
        .toString(props.get(FIRST_NAME_PROP), FIRST_NAME_PROP_DEFAULT);
    lastNameProp = OsgiUtil.toString(props.get(LAST_NAME_PROP), LAST_NAME_PROP_DEFAULT);
    emailProp = OsgiUtil.toString(props.get(EMAIL_PROP), EMAIL_PROP_DEFAULT);
  }

  // ---------- AuthenticationPlugin ----------
  public boolean authenticate(Credentials credentials) throws RepositoryException {
    boolean auth = false;
    if (credentials instanceof SimpleCredentials) {
      // get application user credentials
      String appUser = connMgr.getConfig().getLdapUser();
      String appPass = connMgr.getConfig().getLdapPassword();

      // get user credentials
      SimpleCredentials sc = (SimpleCredentials) credentials;

      if ("admin".equals(sc.getUserID())) {
    	  return false;
      }

      long timeStart = System.currentTimeMillis();

      String userDn = LdapUtil.escapeLDAPSearchFilter(userFilter.replace("{}",
          sc.getUserID()));
      String userPass = new String(sc.getPassword());

      LDAPConnection conn = null;
      try {
        // 0) Get a connection to the server
        try {
          conn = connMgr.getConnection();
          log.debug("Connected to LDAP server");
        } catch (LDAPException e) {
          throw new IllegalStateException("Unable to connect to LDAP server ["
              + connMgr.getConfig().getLdapHost() + "]");
        }

        // 1) Bind as app user
        try {
          conn.bind(LDAPConnection.LDAP_V3, appUser, appPass.getBytes(UTF8));
          log.debug("Bound as application user");
        } catch (LDAPException e) {
          throw new IllegalArgumentException("Can't bind application user [" + appUser
              + "]", e);
        }

        // 2) Search for username (not authz).
        // If search fails, log/report invalid username or password.
        LDAPSearchResults results = conn.search(baseDn, LDAPConnection.SCOPE_SUB, userDn,
            null, true);
        if (results.hasMore()) {
          log.debug("Found user via search");
        } else {
          throw new IllegalArgumentException("Can't find user [" + userDn + "]");
        }

        // 3) Bind as user.
        // If bind fails, log/report invalid username or password.

        // value is set below. define here for use in authz check.
        String userEntryDn = null;
        try {
          // KERN-776 Resolve the user DN from the search results and check for an aliased
          // entry
          LDAPEntry userEntry = results.next();
          LDAPAttribute objectClass = userEntry.getAttribute("objectClass");

          if ("aliasObject".equals(objectClass.getStringValue())) {
            LDAPAttribute aliasDN = userEntry.getAttribute("aliasedObjectName");
            userEntryDn = aliasDN.getStringValue();
          } else {
            userEntryDn = userEntry.getDN();
          }

          conn.bind(LDAPConnection.LDAP_V3, userEntryDn, userPass.getBytes(UTF8));
          log.debug("Bound as user");
        } catch (LDAPException e) {
          log.warn("Can't bind user [{}]", userDn);
          throw e;
        }

        if (authzFilter.length() > 0) {
          // 4) Return to app user
          try {
            conn.bind(LDAPConnection.LDAP_V3, appUser, appPass.getBytes(UTF8));
            log.debug("Rebound as application user");
          } catch (LDAPException e) {
            throw new IllegalArgumentException("Can't bind application user [" + appUser
                + "]");
          }

          // 5) Search user DN with authz filter
          // If search fails, log/report that user is not authorized
          String userAuthzFilter = "(&(" + userEntryDn + ")(" + authzFilter + "))";
          results = conn.search(baseDn, LDAPConnection.SCOPE_SUB, userAuthzFilter, null,
              true);
          if (results.hasMore()) {
            log.debug("Found user + authz filter via search");
          } else {
            throw new IllegalArgumentException("User not authorized [" + userDn + "]");
          }
        }

        // FINALLY!
        auth = true;
        log.info("User [{}] authenticated with LDAP in {}ms", userDn, System.currentTimeMillis() - timeStart);

        if (createAccount) {
          ensureJcrUser(sc.getUserID(), conn);
        }
      } catch (Exception e) {
        log.warn(e.getMessage(), e);
      } finally {
        connMgr.returnConnection(conn);
      }
    }
    return auth;
  }

  /**
   * {@inheritDoc}
   *
   * @see org.apache.sling.commons.auth.spi.AuthenticationFeedbackHandler#authenticationSucceeded(javax.servlet.http.HttpServletRequest,
   *      javax.servlet.http.HttpServletResponse,
   *      org.apache.sling.commons.auth.spi.AuthenticationInfo)
   */
  public Authorizable ensureJcrUser(String userId, LDAPConnection conn) throws Exception {
    Session session = slingRepository.loginAdministrative(null);
    UserManager um = AccessControlUtil.getUserManager(session);
    Authorizable auth = um.getAuthorizable(userId);

    if (auth == null) {
      String password = RandomStringUtils.random(8);
      auth = um.createUser(userId, password);

      if (decorateUser) {
        decorateUser(session, auth, conn);
      }

      String userPath = AuthorizableResourceProvider.SYSTEM_USER_MANAGER_USER_PREFIX
          + auth.getID();
      authzPostProcessorService.process(auth, session, Modification.onCreated(userPath));
    }
    return auth;
  }

  /**
   * Decorate the user with extra information.
   *
   * @param session
   * @param user
   */
  private void decorateUser(Session session, Authorizable user, LDAPConnection conn)
      throws RepositoryException {
    try {
      // fix up the user dn to search
      String userDn = LdapUtil.escapeLDAPSearchFilter(userFilter.replace("{}",
          user.getID()));

      // get a connection to LDAP
      LDAPSearchResults results = conn.search(baseDn, LDAPConnection.SCOPE_SUB, userDn,
          new String[] { firstNameProp, lastNameProp, emailProp }, false);
      if (results.hasMore()) {
        LDAPEntry entry = results.next();
        ValueFactory vf = session.getValueFactory();

        String firstName = entry.getAttribute(firstNameProp).getStringValue();
        String lastName = entry.getAttribute(lastNameProp).getStringValue();
        String email = entry.getAttribute(emailProp).getStringValue();

        user.setProperty("firstName", vf.createValue(firstName));
        user.setProperty("lastName", vf.createValue(lastName));
        user.setProperty("email", vf.createValue(email));
      } else {
        log.warn("Can't find user [" + userDn + "]");
      }
    } catch (LDAPException e) {
      log.warn(e.getMessage(), e);
    } catch (RepositoryException e) {
      log.warn(e.getMessage(), e);
    } finally {
      if (session.hasPendingChanges()) {
        session.save();
      }
    }
  }
}
