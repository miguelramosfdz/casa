package org.gluu.casa.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gluu.casa.conf.sndfactor.EnforcementPolicy;
import org.gluu.casa.conf.sndfactor.TrustedDevice;
import org.gluu.casa.conf.sndfactor.TrustedDeviceComparator;
import org.gluu.casa.core.model.Person;
import org.gluu.casa.core.model.PersonPreferences;
import org.gluu.casa.core.pojo.User;
import org.gluu.casa.extension.AuthnMethod;
import org.gluu.casa.misc.Utils;
import org.gluu.casa.misc.WebUtils;
import org.gluu.casa.plugins.authnmethod.SecurityKeyExtension;
import org.gluu.casa.plugins.authnmethod.SuperGluuExtension;
import org.gluu.search.filter.Filter;
import org.slf4j.Logger;
import org.zkoss.util.Pair;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;
import javax.management.AttributeNotFoundException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An app. scoped bean that encapsulates logic related to users manipulation (CRUD) at memory level (no LDAP storage)
 * @author jgomer
 */
@Named
@ApplicationScoped
public class UserService {

    private static final String PREFERRED_METHOD_ATTR = "oxPreferredMethod";

    @Inject
    private Logger logger;

    @Inject
    private PersistenceService persistenceService;

    @Inject
    private ExtensionsManager extManager;

    @Inject
    private ConfigurationHandler confHandler;

    private ObjectMapper mapper = new ObjectMapper();

    public User getUserFromClaims(Map<String, List<String>> claims) throws AttributeNotFoundException {

        User u = new User();
        u.setUserName(getClaim(claims, "user_name"));
        logger.trace("Creating a user instance from claims. Username is {}", u.getUserName());

        String img = getClaim(claims, "picture");
        if (Utils.isNotEmpty(img)) {
            u.setPictureURL(WebUtils.validateImageUrl(img));
        }

        u.setLastName(getClaim(claims, "family_name"));
        u.setGivenName(getClaim(claims, "given_name"));
        String inum = getClaim(claims, "inum");

        if (inum != null) {
            u.setId(inum);
        }
        if (u.getId() == null || u.getUserName() == null) {
            logger.error("Could not obtain minimal user claims!");
            throw new AttributeNotFoundException("Cannot retrieve claims for logged user");
        }
        PersonPreferences person = personPreferencesInstance(inum);
        if (person == null) {
            throw new AttributeNotFoundException("Cannot retrieve user's info from LDAP");
        }

        u.setPreferredMethod(person.getPreferredMethod());
        u.setAdmin(persistenceService.isAdmin(inum));
        cleanRandEnrollmentCode(inum);
        if (confHandler.getSettings().getAcrPluginMap().keySet().stream()
                .anyMatch(acr -> acr.equals(SecurityKeyExtension.ACR) || acr.equals(SuperGluuExtension.ACR))) {
            persistenceService.prepareFidoBranch(inum);
        }
        return u;

    }

    public List<AuthnMethod> getLiveAuthnMethods() {
        return getLiveAuthnMethods(true);
    }

    public List<AuthnMethod> get2FARequisiteMethods() {
        return getLiveAuthnMethods(false).stream().filter(AuthnMethod::mayBe2faActivationRequisite).collect(Collectors.toList());
    }

    public List<Pair<AuthnMethod, Integer>> getUserMethodsCount(String userId) {
        return getLiveAuthnMethods(false).stream()
                .map(aMethod -> new Pair<>(aMethod, aMethod.getTotalUserCreds(userId)))
                .filter(pair -> pair.getY() > 0).collect(Collectors.toList());
    }

    public boolean setPreferredMethod(User user, String method) {

        boolean success = setPreferredMethod(user.getId(), method);
        if (success) {
            user.setPreferredMethod(method);
        }
        return success;

    }

    /**
     * Resets the preferred method of authentication for the users referenced by LDAP dn
     * @param userInums A List containing user DNs
     * @return The number of modified entries in LDAP
     */
    public int resetPreference(List<String> userInums) {

        int modified = 0;
        try {
            for (String inum : userInums) {
                if (setPreferredMethod(inum, null)) {
                    modified++;
                    logger.info("Turned 2FA off for user '{}'", inum);
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return modified;

    }

    /**
     * Builds a list of users whose username, first or last name matches the pattern passed, and at the same time have a
     * preferred authentication method other than password
     * @param searchString Pattern for search
     * @return A collection of SimpleUser instances. Null if an error occurred to compute the list
     */
    public List<Person> searchUsers(String searchString) {

        Stream<Filter> stream = Stream.of("uid", "givenName", "sn")
                .map(attr -> Filter.createSubstringFilter(attr, null, new String[]{ searchString }, null));

        Filter filter = Filter.createANDFilter(
                Filter.createORFilter(stream.collect(Collectors.toList())),
                Filter.createPresenceFilter(PREFERRED_METHOD_ATTR)
        );
        return persistenceService.find(Person.class, persistenceService.getPeopleDn(), filter);

    }

    private boolean setPreferredMethod(String id, String method) {

        boolean success = false;
        try {
            PersonPreferences person = personPreferencesInstance(id);
            person.setPreferredMethod(method);
            success = persistenceService.modify(person);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return success;

    }

    public Pair<Set<String>, List<TrustedDevice>> get2FAPolicyData(String userId) {

        Set<String> list = new HashSet<>();
        List<TrustedDevice> trustedDevices = new ArrayList<>();
        try {
            PersonPreferences person = personPreferencesInstance(userId);
            String policy = person.getStrongAuthPolicy();

            if (Utils.isNotEmpty(policy)) {
                Stream.of(policy.split(",\\s*")).forEach(str -> {
                    try {
                        list.add(EnforcementPolicy.valueOf(str.toUpperCase()).toString());
                    } catch (Exception e) {
                        logger.error("The policy '{}' is not recognized", str);
                    }
                });
            }

            String trustedDevicesInfo = persistenceService.getDecryptedString(person.getTrustedDevices());
            if (Utils.isNotEmpty(trustedDevicesInfo)) {
                trustedDevices = mapper.readValue(trustedDevicesInfo, new TypeReference<List<TrustedDevice>>() { });
                trustedDevices.forEach(TrustedDevice::sortOriginsDescending);

                TrustedDeviceComparator comparator = new TrustedDeviceComparator(true);
                trustedDevices.sort((first, second) -> comparator.compare(second, first));
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return new Pair<>(list, trustedDevices);

    }

    public boolean update2FAPolicies(String userId, Set<String> policies) {

        boolean updated = false;
        String str = policies.stream().map(String::toLowerCase).reduce("", (partial, next) -> partial + ", " + next);
        try {
            PersonPreferences person = personPreferencesInstance(userId);
            person.setStrongAuthPolicy(str.substring(2));
            updated = persistenceService.modify(person);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return updated;

    }

    public boolean deleteTrustedDevice(String userId, List<TrustedDevice> devices, int index) {

        boolean updated = false;
        List<TrustedDevice> copyOfDevices = new ArrayList<>(devices);
        try {
            copyOfDevices.remove(index);
            String updatedJson = persistenceService.getEncryptedString(mapper.writeValueAsString(copyOfDevices));

            PersonPreferences person = personPreferencesInstance(userId);
            person.setTrustedDevices(updatedJson);
            if (persistenceService.modify(person)) {
                devices.remove(index);
                updated = true;
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return updated;

    }

    public String generateRandEnrollmentCode(String userId) {

        logger.debug("Writing random enrollment code for {}", userId);
        String code = UUID.randomUUID().toString();
        Person person = persistenceService.get(Person.class, persistenceService.getPersonDn(userId));
        person.setEnrollmentCode(code);
        return persistenceService.modify(person) ? code : null;

    }

    public void cleanRandEnrollmentCode(String userId) {
        Person person = persistenceService.get(Person.class, persistenceService.getPersonDn(userId));

        if (Utils.isNotEmpty(person.getEnrollmentCode())) {
            logger.trace("Removing enrollment code for {}", userId);
            person.setEnrollmentCode(null);
            persistenceService.modify(person);
        }
    }

    private List<AuthnMethod> getLiveAuthnMethods(boolean sorted) {

        List<AuthnMethod> methods = new ArrayList<>();
        Map<String, String> acrPluginMap = confHandler.getSettings().getAcrPluginMap();

        for (String acr : acrPluginMap.keySet()) {
            extManager.getAuthnMethodExts(Collections.singleton(acrPluginMap.get(acr)))
                    .stream().filter(am -> am.getAcr().equals(acr)).findFirst().ifPresent(methods::add);
        }

        if (sorted) {
            //An important invariant is that mappedAcrs set is fully contained in authnMethodLevels.keySet()
            Map<String, Integer> authnMethodLevels = confHandler.getAcrLevelMapping();
            methods = methods.stream().sorted(Comparator.comparing(aMethod -> -authnMethodLevels.get(aMethod.getAcr())))
                    .collect(Collectors.toList());
        }
        return methods;

    }

    /**
     * From a collection of claims, it extracts the first value found for a claim whose name is passed. If claim is not
     * found or has an empty list associated, it returns null
     * @param claims Map with claims (as gathered via oxd)
     * @param claimName Claim to inspect
     * @return First value of claim or null
     */
    private String getClaim(Map<String, List<String>> claims, String claimName) {
        List<String> values = claims.get(claimName);
        return Utils.isEmpty(values) ? null : values.get(0);
    }

    private PersonPreferences personPreferencesInstance(String id) {
        return persistenceService.get(PersonPreferences.class, persistenceService.getPersonDn(id));
    }

}
