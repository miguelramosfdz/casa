package org.gluu.casa.core.ldap;

import com.unboundid.ldap.sdk.ReadOnlyEntry;
import com.unboundid.ldap.sdk.persist.*;

/**
 * <p>This class is <b>deprecated</b>. Use {@link org.gluu.casa.core.model.BasePerson} instead.</p>
 * This class provides an implementation of an object that can be used to
 * represent gluuPerson objects in the directory.
 * It was generated by the generate-source-from-schema tool provided with the
 * UnboundID LDAP SDK for Java.  It may be customized as desired to better suit
 * your needs.
 */
@Deprecated
@LDAPObject(structuralClass="gluuPerson",
        superiorClass="top")
public class BaseLdapPerson {

    // The field to use to hold a read-only copy of the associated entry.
    @LDAPEntryField
    private ReadOnlyEntry ldapEntry;

    // The field used for RDN attribute ou.
    @LDAPField(inRDN=true,
            filterUsage= FilterUsage.ALWAYS_ALLOWED,
            requiredForEncode=true)
    private String[] inum;

    // The field used for optional attribute uid.
    @LDAPField
    private String[] uid;

    /**
     * Retrieves the first value for the field associated with the
     * inum attribute, if present.
     *
     * @return  The first value for the field associated with the
     *          inum attribute, or
     *          {@code null} if that attribute was not present in the entry or
     *          does not have any values.
     */
    public String getInum()
    {
        if ((inum == null) ||
                (inum.length == 0))
        {
            return null;
        }
        else
        {
            return inum[0];
        }
    }

    /**
     * Retrieves the first value for the field associated with the
     * uid attribute, if present.
     *
     * @return  The first value for the field associated with the
     *          uid attribute, or
     *          {@code null} if that attribute was not present in the entry or
     *          does not have any values.
     */
    public String getUid()
    {
        if ((uid== null) ||
                (uid.length == 0))
        {
            return null;
        }
        else
        {
            return uid[0];
        }
    }

}
