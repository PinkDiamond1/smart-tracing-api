package io.zerobase.smarttracing

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.zerobase.smarttracing.models.*
import io.zerobase.smarttracing.utils.LoggerDelegate
import org.apache.tinkerpop.gremlin.process.traversal.Traversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.structure.T
import org.apache.tinkerpop.gremlin.structure.VertexProperty
import java.util.*

private fun <T> VertexProperty<T>.getIfPresent(): T? {
    return if (isPresent) { value() } else { null }
}

private fun <S, T> Traversal<S, T>.getIfPresent(): T? {
    return tryNext().orElse(null)
}

class GraphDao(private val graph: GraphTraversalSource, private val phoneUtil: PhoneNumberUtil) {
    companion object {
        private val log by LoggerDelegate()
    }

    /**
     * Creates a new Device and returns its ID
     */
    fun createDevice(fingerprint: Fingerprint?, ip: String?): DeviceId {
        val id = UUID.randomUUID().toString()
        try {
            val vertex = graph.addV("Device")
                    .property(T.id, id)
                    .property("fingerprint", fingerprint?.value ?: "none")
                    .property("creationTimestamp", System.currentTimeMillis())
                    .next()
            return vertex?.run { DeviceId(id) } ?: throw EntityCreationException("Failed to save device")
        } catch (ex: Exception) {
            log.error("error creating device. fingerprint={}", fingerprint, ex)
            throw EntityCreationException("Error creating device", ex)
        }
    }

    /**
     * Creates a new CheckIn and returns its ID
     */
    @Deprecated(message = "Legacy API endpoint", replaceWith = ReplaceWith("recordPeerToPerScan"))
    fun createCheckIn(deviceId: DeviceId, scannedId: ScannableId, loc: Location?): ScanId {
        val scanId = UUID.randomUUID().toString()
        try {
            graph.addE("SCAN")
                    .from(graph.V(deviceId.value))
                    .to(graph.V(scannedId.value))
                    .property(T.id, scanId)
                    .property("timestamp", System.currentTimeMillis())
                    .property("latitude", loc?.latitude)
                    .property("longitude", loc?.longitude)
                    .next()
            return ScanId(scanId)
        } catch (ex: Exception) {
            log.error("error creating check-in. device={} scannable={}", deviceId, scannedId, ex)
            throw EntityCreationException("Error creating check-in", ex)
        }
    }

    /**
     * Updates the location attribute of a CheckIn. Throws 404 if CheckIn doesn't exist.
     */
    fun updateCheckInLocation(deviceId: DeviceId, checkInId: ScanId, loc: Location) {
        graph.V(T.id, checkInId.value).inE("SCAN")
                .from(graph.V(T.id, deviceId.value))
                .property("latitude", loc.latitude)
                .property("longitude", loc.longitude)
                .next()
    }

    fun recordPeerToPeerScan(scanner: DeviceId, scanned: DeviceId, loc: Location?): ScanId? {
        val scanId = UUID.randomUUID().toString()
        try {
            graph.addE("SCAN")
                    .from(graph.V(scanner.value))
                    .to(graph.V(scanned.value))
                    .property(T.id, scanId)
                    .property("timestamp", System.currentTimeMillis())
                    .property("latitude", loc?.latitude)
                    .property("longitude", loc?.longitude)
                    .next()
            return ScanId(scanId)
        } catch (ex: Exception) {
            log.error("error creating p2p scan. scanner={} scanned={}", scanner, scanned, ex)
            throw EntityCreationException("Error creating scan relationship between devices.", ex)
        }
    }

    fun recordDeviceCheckIn(device: DeviceId, site: SiteId): ScanId {
        val id = UUID.randomUUID().toString()
        try {
            graph.addE("SCAN")
                    .from(graph.V(T.id, device.value))
                    .to(graph.V(T.id, site.value))
                    .property(T.id, id)
                    .property("timestamp", System.currentTimeMillis())
                    .next()
            return ScanId(id)
        } catch (ex: Exception) {
            log.error("error creating site scan. device={} site={}", device, site, ex)
            throw EntityCreationException("Error creating scan between device and site.", ex);
        }
    }

    /**
     * Creates an organization node inside neo4j.
     *
     * @param organization name of the organization that is being created.
     * @param phone phone number for the contact for the organization.
     * @param email email for the contact for the organization.
     * @param contactName name of the contact for the organization.
     * @param address address of the organization.
     * @param hasTestingFacilities if the organization owns sites that can do testing.
     * @param multiSite reporting if the organization has multiple sites.
     *
     * @return organization id.
     *
     * @throws exception if phone number is invalid.
     */
    fun createOrganization(name: String, phone: String, email: String, contactName: String, address: String,
                           hasTestingFacilities: Boolean, multiSite: Boolean): OrganizationId {

        validatePhoneNumber(phone)

        val id = UUID.randomUUID().toString()
        try {
            graph.addV("Organization")
                .property(T.id, id)
                .property("name", name)
                .property("address", address)
                .property("contactName", contactName)
                .property("phone", phone)
                .property("email", email)
                .property("verified", false)
                .property("hasTestingFacilities", hasTestingFacilities)
                .property("multisite", multiSite)
                .property("creationTimestamp", System.currentTimeMillis())
                .next()
            return OrganizationId(id)
        } catch (ex: Exception) {
            log.error("Error creating organization. name={}", name, ex)
            throw EntityCreationException("Error creating organization", ex)
        }
    }

    /**
     * Sets the multi-site flag in an organization.
     *
     * @param id organization uuid.
     * @param state the value for the multi-site flag
     */
    fun setMultiSite(id: OrganizationId, state: Boolean) {
        graph.V(T.id, id.value).property("multisite", state).next()
    }

    /**
     * Creates site.
     *
     * @param id organization id.
     * @param name name of the site.
     * @param category category of the site.
     * @param subcategory subcategory of the site.
     * @param lat latitude of the site.
     * @param long longitude of the site.
     * @param testing whether the site can preform testing.
     * @param phone contact phone of site manager
     * @param email contact email of site manager
     * @param contactName contact name of site manager
     */
    fun createSite(organizationId: OrganizationId, name: String, category: String, subcategory: String, lat: Float, long: Float,
                   testing: Boolean, phone: String?, email: String?, contactName: String?): SiteId {
        val id = UUID.randomUUID().toString()
        try {
            val siteVertex = graph.addV("Site")
                .property(T.id, id)
                .property("latitude", lat)
                .property("longitude", long)
                .property("category", category)
                .property("subcategory", subcategory)
                .property("latitude", lat)
                .property("longitude", long)
                .property("contactName", contactName)
                .property("phone", phone)
                .property("email", email)
                .property("testing", testing)
                .property("creationTimestamp", System.currentTimeMillis())
                .next()
            graph.addE("OWNS").from(graph.V(T.id, organizationId.value)).to(siteVertex)
            return SiteId(id)
        } catch (ex: Exception) {
            log.error("error creating site. organization={} name={} category={}-{} testing={}", id, name, category, subcategory, testing, ex)
            throw EntityCreationException("Error creating site.", ex)
        }
    }

    /**
     * Gets all the sites list
     *
     * @param id id of the organization
     *
     * @return list of all the sites.
     */
    fun getSites(id: OrganizationId): List<Pair<String, String>> {
        return graph.V(T.id, id.value).outE("OWNS").otherV().toList()
            .map{ "${it.id()}" to it.property<String>("name").value() }
    }

    /**
     * Creates a scannable for a site. A scannable is either QR Code or BT
     * receivers.
     *
     * @param oid organization id
     * @param sid site id
     * @param type type of scannable
     * @param singleUse if it is a single use scannable or not
     *
     * @return id of the scannable.
     */
    fun createScannable(oid: OrganizationId, sid: SiteId, type: String, singleUse: Boolean): ScannableId {
        val id = UUID.randomUUID().toString()
        try {
            graph.addV("Scannable").property(T.id, id).property("type", type).property("singleUse", singleUse)
                .property("active", true).addE("OWNS")
                .from(graph.V(T.id, sid.value))
                .next()
            return ScannableId(id)
        } catch (ex: Exception) {
            log.error("error creating scannable. organization={} site={} type={}", oid, sid, type)
            throw EntityCreationException("Error creating scannable.", ex)
        }
    }

    /**
     * Creates a user node and links to device id.
     *
     * @param name name of the user.
     * @param phone phone of the user.
     * @param email email of the user.
     * @param id id of the device used to create it.
     *
     * @returns id of the user.
     */
    fun createUser(name: String?, phone: String?, email: String?, deviceId: DeviceId): UserId {
        phone?.apply { validatePhoneNumber(phone) }
        val id = UUID.randomUUID().toString()
        try {
            graph.addV("USER")
                .property(T.id, id).property("name", name).property("phone", phone).property("email", email)
                .property("deleted", false)
                .addE("OWNS").from(graph.V(T.id, deviceId))
                .next()
            return UserId(id)
        } catch (ex: Exception) {
            log.error("error creating user for device. device={}", deviceId, ex)
            throw EntityCreationException("Error creating user.", ex)
        }
    }

    /**
     * "Deletes" the user
     *
     * @param id id of the user to delete
     */
    fun deleteUser(id: UserId) {
        try {
            graph.V(T.id, id.value).property("deleted", true).next()
        } catch (ex: Exception) {
            log.error("failed to delete user. id={}", id, ex)
            throw ex
        }
    }

    /**
     * Gets the user
     *
     * @param id the id of the user
     *
     * @return User struct
     */
    fun getUser(id: UserId): User? {
        try {
            val vertex = graph.V(T.id, id.value).has("deleted", false).getIfPresent() ?: return null
            return User(
                id = id,
                name = vertex.property<String>("name").value(),
                phone = vertex.property<String>("phone").getIfPresent(),
                email = vertex.property<String>("email").getIfPresent()
            )
        } catch (ex: Exception) {
            log.error("error getting user. id={}", ex)
            throw ex
        }
    }

    private fun validatePhoneNumber(phone: String) {
        try {
            // ZZ as the region code forces E.164
            val parsedPhoneNumber = phoneUtil.parse(phone, "ZZ")
            val validityResult = phoneUtil.isPossibleNumberWithReason(parsedPhoneNumber)
            if (validityResult != PhoneNumberUtil.ValidationResult.IS_POSSIBLE) {
                throw InvalidPhoneNumberException("Unable to validate phone number: $validityResult")
            }
        } catch (ex: NumberParseException) {
            throw InvalidPhoneNumberException("Phone number could not be parsed: ${ex.message}")
        }
    }
}
