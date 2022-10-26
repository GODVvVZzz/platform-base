package xyz.eulix.platform.services.registry.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import java.util.Optional;
import xyz.eulix.platform.services.registry.entity.RegistryClientEntity;

import javax.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Set;

/**
 * All Registry Entity related storage operations including standard CRUD and
 * other customized operations such as state transition and updating.
 */
@ApplicationScoped
public class RegistryClientEntityRepository implements PanacheRepository<RegistryClientEntity> {
    // 根据box_uuid、user_id、client_uuid、box_reg_key查询资源
    private static final String FIND_BY_CLIENTUUID_CLIENTREGKEY = "box_uuid=?1 AND user_id=?2 AND client_uuid=?3 AND client_reg_key=?4";

    // 根据box_uuid、user_id、client_uuid查询资源
    private static final String FIND_BY_CLIENTUUID = "box_uuid=?1 AND user_id=?2 AND client_uuid=?3";

    // 根据box_uuid、user_id查询资源
    private static final String FIND_BY_BOXUUID_USERID = "box_uuid=?1 AND user_id=?2";

    // 根据box_uuid查询资源
    private static final String FIND_BY_BOXUUID = "box_uuid=?1";

    // 根据box_uuids、type查询资源
    private static final String FIND_BY_BOXUUIDS_TYPE = "box_uuid in (?1) AND type=?2";

    public List<RegistryClientEntity> findAllByClientUUID(String boxUUID, String userId, String clientUUID) {
        return this.find(FIND_BY_CLIENTUUID, boxUUID, userId, clientUUID).list();
    }

    public Optional<RegistryClientEntity> findByBoxUUIDAndUserIdAndClientUUID(String boxUUID, String userId, String clientUUID) {
        return this.find(FIND_BY_CLIENTUUID, boxUUID, userId, clientUUID).firstResultOptional();
    }
    public List<RegistryClientEntity> findAllByClientUUIDAndClientRegKey(String boxUUID, String userId, String clientUUID, String clientRegKey) {
        return this.find(FIND_BY_CLIENTUUID_CLIENTREGKEY, boxUUID, userId, clientUUID, clientRegKey).list();
    }

    public void deleteByClientUUID(String boxUUID, String userId, String clientUUID) {
        this.delete(FIND_BY_CLIENTUUID, boxUUID, userId, clientUUID);
    }

    public void deleteByUserId(String boxUUID, String userId) {
        this.delete(FIND_BY_BOXUUID_USERID, boxUUID, userId);
    }

    public List<RegistryClientEntity> findByBoxUUIdAndUserId(String boxUUID, String userId){
        return this.find(FIND_BY_BOXUUID_USERID, boxUUID, userId).list();
    }

    public void deleteByBoxUUID(String boxUUID) {
        this.delete(FIND_BY_BOXUUID, boxUUID);
    }

    public List<RegistryClientEntity> findByBoxUUIDsAndType(Set<String> boxUUIDs, String type) {
        return this.find(FIND_BY_BOXUUIDS_TYPE, boxUUIDs, type).list();
    }
}