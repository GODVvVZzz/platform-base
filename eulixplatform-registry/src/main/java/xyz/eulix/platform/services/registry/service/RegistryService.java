package xyz.eulix.platform.services.registry.service;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.jboss.logging.Logger;
import xyz.eulix.platform.services.config.ApplicationProperties;
import xyz.eulix.platform.services.network.service.NetworkService;
import xyz.eulix.platform.services.provider.ProviderFactory;
import xyz.eulix.platform.services.provider.inf.RegistryProvider;
import xyz.eulix.platform.services.registry.dto.registry.*;
import xyz.eulix.platform.services.registry.dto.registry.v2.BoxRegistryResultV2;
import xyz.eulix.platform.services.registry.dto.registry.v2.UserRegistryInfoV2;
import xyz.eulix.platform.services.registry.dto.registry.v2.UserRegistryResultV2;
import xyz.eulix.platform.services.token.dto.ServiceEnum;
import xyz.eulix.platform.services.token.entity.BoxTokenEntity;
import xyz.eulix.platform.services.registry.entity.RegistryBoxEntity;
import xyz.eulix.platform.services.registry.entity.RegistryClientEntity;
import xyz.eulix.platform.services.registry.entity.RegistryUserEntity;
import xyz.eulix.platform.services.registry.entity.SubdomainEntity;
import xyz.eulix.platform.services.registry.repository.RegistryBoxEntityRepository;
import xyz.eulix.platform.services.registry.repository.RegistryClientEntityRepository;
import xyz.eulix.platform.services.registry.repository.RegistryUserEntityRepository;
import xyz.eulix.platform.services.registry.repository.SubdomainEntityRepository;
import xyz.eulix.platform.common.support.CommonUtils;
import xyz.eulix.platform.common.support.service.ServiceError;
import xyz.eulix.platform.common.support.service.ServiceOperationException;
import xyz.eulix.platform.services.token.service.TokenService;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.PersistenceException;
import javax.transaction.Transactional;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides box and client registry service.
 */
@ApplicationScoped
public class RegistryService {
    private static final Logger LOG = Logger.getLogger("app.log");

    // 盒子可申请的subdomain数量上限
    private static final Integer SUBDOMAIN_UPPER_LIMIT = 1000;

    // 有效期，单位秒，最长7天
    private static final Integer MAX_EFFECTIVE_TIME = 7 * 24 * 60 * 60;

    // 推荐subdomain数量上限
    private static final Integer RECOMMEND_SUBDOMAIN_UPPER_LIMIT = 5;

    // 推荐subdomain重试上限
    private static final Integer RECOMMEND_SUBDOMAIN_RETRYS = 10;

    // 推荐subdomain最短长度
    private static final Integer RECOMMEND_SUBDOMAIN_MIN_LENGTH = 6;

    // 推荐subdomain最大长度
    private static final Integer RECOMMEND_SUBDOMAIN_MAX_LENGTH = 20;

    private final static Random random = new java.security.SecureRandom();

    // 批量查询开始编号
    private static final Integer BEGIN_INDEX = 0;

    // 批量查询数量限制
    private static final Integer PAGE_SIZE = 2000;

    @Inject
    ApplicationProperties properties;

    @Inject
    RegistryBoxEntityRepository boxEntityRepository;

    @Inject
    RegistryUserEntityRepository userEntityRepository;

    @Inject
    RegistryClientEntityRepository clientEntityRepository;

    @Inject
    SubdomainEntityRepository subdomainEntityRepository;

    @Inject
    SubdomainService subdomainService;

    @Inject
    NetworkService networkService;

    @Inject
    ProviderFactory providerFactory;

    @Inject
    TokenService tokenService;

    public boolean isValidBoxUUID(String boxUUID) {
        RegistryProvider registryProvider = providerFactory.getRegistryProvider();
        return registryProvider.isBoxIllegal(boxUUID);
    }

    public boolean verifyClient(String clientRegKey, String boxUUID, String userId, String clientUUID) {
        List<RegistryClientEntity> clientEntities = clientEntityRepository.findAllByClientUUIDAndClientRegKey(boxUUID, userId, clientUUID, clientRegKey);
        return !clientEntities.isEmpty();
    }

    public boolean verifyClient(String boxUUID, String userId, String clientUUID) {
        List<RegistryClientEntity> clientEntities = clientEntityRepository.findAllByClientUUID(boxUUID, userId, clientUUID);
        return !clientEntities.isEmpty();
    }

    public boolean verifyUser(String userRegKey, String boxUUID, String userId) {
        List<RegistryUserEntity> userEntities = userEntityRepository.findAllByUserIDAndUserRegKey(boxUUID, userId, userRegKey);
        return !userEntities.isEmpty();
    }

    public boolean verifyUser(String boxUUID, String userId) {
        List<RegistryUserEntity> userEntities = userEntityRepository.findAllByUserId(boxUUID, userId);
        return !userEntities.isEmpty();
    }

    @Transactional
    public void deleteClientByClientUUID(String boxUUID, String userId, String clientUUID) {
        clientEntityRepository.deleteByClientUUID(boxUUID, userId, clientUUID);
    }

    @Transactional
    public BoxRegistryResult registryBox(BoxRegistryInfo info) {
        // 注册box
        RegistryBoxEntity boxEntity = registryBox(info.getBoxUUID());
        // 计算路由
        networkService.calculateNetworkRoute(boxEntity.getNetworkClientId());
        // 同步写入写入box_info表，有效期24h
        tokenService.createBoxToken(info.getBoxUUID(), ServiceEnum.REGISTRY, boxEntity.getBoxRegKey());
        return BoxRegistryResult.of(boxEntity.getBoxRegKey(), NetworkClient.of(boxEntity.getNetworkClientId(), boxEntity.getNetworkSecretKey()));
    }

    @Transactional
    public BoxRegistryResultV2 registryBoxV2(BoxTokenEntity boxToken) {
        var registryBoxEntity = boxEntityRepository.findByBoxUUID(boxToken.getBoxUUID());
        if (registryBoxEntity.isPresent()) {
            return BoxRegistryResultV2.of(
                    registryBoxEntity.get().getBoxUUID(),
                    NetworkClient.of(registryBoxEntity.get().getNetworkClientId(), registryBoxEntity.get().getNetworkSecretKey()));
        }
        // 注册box
        RegistryBoxEntity boxEntity = registryBox(boxToken.getBoxUUID(), boxToken.getBoxRegKey());
        // 计算路由
        networkService.calculateNetworkRoute(boxEntity.getNetworkClientId());
        return BoxRegistryResultV2.of(boxEntity.getBoxUUID(), NetworkClient.of(boxEntity.getNetworkClientId(), boxEntity.getNetworkSecretKey()));
    }

    @Transactional
    public UserRegistryResult registryUser(UserRegistryInfo userRegistryInfo, String userDomain) {
        // 注册用户
        RegistryUserEntity userEntity = registryUser(userRegistryInfo.getBoxUUID(), userRegistryInfo.getUserId(),
                RegistryTypeEnum.fromValue(userRegistryInfo.getUserType()));

        // 修改域名状态
        subdomainEntityRepository.updateBySubdomain(userRegistryInfo.getUserId(), SubdomainStateEnum.USED.getState(),
                userRegistryInfo.getSubdomain());

        // 添加用户面路由：用户域名 - network server 地址 & network client id
        networkService.cacheNSRoute(userDomain, userRegistryInfo.getBoxUUID());

        // 注册client（用户的绑定设备）
        RegistryClientEntity clientEntity = registryClient(userRegistryInfo.getBoxUUID(), userRegistryInfo.getUserId(), userRegistryInfo.getClientUUID(),
                RegistryTypeEnum.CLIENT_BIND);

        return UserRegistryResult.of(userDomain, userEntity.getUserRegKey(), clientEntity.getClientRegKey());
    }

    @Transactional
    public UserRegistryResultV2 registryUserV2(UserRegistryInfoV2 userRegistryInfo, String boxUUID) {

        Optional<RegistryUserEntity> userRegistryEntityOp = userEntityRepository.findUserByBoxUUIDAndUserId(boxUUID, userRegistryInfo.getUserId());

        if (userRegistryEntityOp.isPresent()) {
            resetUserInner(boxUUID, userRegistryInfo.getUserId());
        }

        SubdomainEntity subdomainEntity;
        if (CommonUtils.isNullOrEmpty(userRegistryInfo.getSubdomain())) {
            // 申请subdomain
            subdomainEntity = subdomainGen(boxUUID);
            userRegistryInfo.setSubdomain(subdomainEntity.getSubdomain());
        } else {
            // 校验subdomain是否不存在，或者已使用
            subdomainEntity = isSubdomainNotExistOrUsed(userRegistryInfo.getSubdomain());
        }

        // 注册用户
        RegistryUserEntity userEntity = registryUser(boxUUID, userRegistryInfo.getUserId(),
                RegistryTypeEnum.fromValue(userRegistryInfo.getUserType()));

        // 修改域名状态
        subdomainEntityRepository.updateBySubdomain(userRegistryInfo.getUserId(), SubdomainStateEnum.USED.getState(),
                userRegistryInfo.getSubdomain());

        // 添加用户面路由：用户域名 - network server 地址 & network client id
        networkService.cacheNSRoute(subdomainEntity.getUserDomain(), boxUUID);

        // 注册client（用户的绑定设备）
        RegistryClientEntity clientEntity = registryClientV2(boxUUID, userRegistryInfo.getUserId(),
                userRegistryInfo.getClientUUID(), RegistryTypeEnum.CLIENT_BIND);

        return UserRegistryResultV2.of(boxUUID, userEntity.getUserId(), subdomainEntity.getUserDomain(), userEntity.getRegistryType(), clientEntity.getClientUUID());
    }

    @Transactional
    public RegistryBoxEntity registryBox(String boxUUID) {
        // 注册box
        RegistryBoxEntity boxEntity = new RegistryBoxEntity();
        {
            boxEntity.setBoxUUID(boxUUID);
            boxEntity.setBoxRegKey("brk_" + CommonUtils.createUnifiedRandomCharacters(10));
            // network client
            boxEntity.setNetworkClientId(CommonUtils.getUUID());
            boxEntity.setNetworkSecretKey("nrk_" + CommonUtils.createUnifiedRandomCharacters(10));
        }
        boxEntityRepository.persist(boxEntity);
        return boxEntity;
    }

    @Transactional
    public RegistryBoxEntity registryBox(String boxUUID, String boxRegKey) {
        // 注册box
        RegistryBoxEntity boxEntity = new RegistryBoxEntity();
        {
            boxEntity.setBoxUUID(boxUUID);
            boxEntity.setBoxRegKey(boxRegKey);
            // network client
            boxEntity.setNetworkClientId(CommonUtils.getUUID());
            boxEntity.setNetworkSecretKey("nrk_" + CommonUtils.createUnifiedRandomCharacters(10));
        }
        boxEntityRepository.persist(boxEntity);
        return boxEntity;
    }

    @Transactional
    public RegistryUserEntity registryUser(String boxUUID, String userId, RegistryTypeEnum userType) {
        RegistryUserEntity userEntity = new RegistryUserEntity();
        {
            userEntity.setBoxUUID(boxUUID);
            userEntity.setUserId(userId);
            userEntity.setUserRegKey("urk_" + CommonUtils.createUnifiedRandomCharacters(10));
            userEntity.setRegistryType(userType.getName());
        }
        userEntityRepository.persist(userEntity);
        return userEntity;
    }

    @Transactional
    public RegistryClientEntity registryClient(String boxUUID, String userId, String clientUUID, RegistryTypeEnum clientType) {
        RegistryClientEntity clientEntity = new RegistryClientEntity();
        {
            clientEntity.setBoxUUID(boxUUID);
            clientEntity.setUserId(userId);
            clientEntity.setClientUUID(clientUUID);
            clientEntity.setClientRegKey("crk_" + CommonUtils.createUnifiedRandomCharacters(10));
            clientEntity.setRegistryType(clientType.getName());
        }
        clientEntityRepository.persist(clientEntity);
        return clientEntity;
    }

    @Transactional
    public RegistryClientEntity registryClientV2(String boxUUID, String userId, String clientUUID, RegistryTypeEnum clientType) {
        var registryClientEntity = clientEntityRepository.findByBoxUUIDAndUserIdAndClientUUID(
                boxUUID, userId, clientUUID);
        if (registryClientEntity.isPresent()) {
            clientEntityRepository.deleteByClientUUID(boxUUID, userId, clientUUID);
        }
        RegistryClientEntity clientEntity = new RegistryClientEntity();
        {
            clientEntity.setBoxUUID(boxUUID);
            clientEntity.setUserId(userId);
            clientEntity.setClientUUID(clientUUID);
            clientEntity.setClientRegKey("crk_" + CommonUtils.createUnifiedRandomCharacters(10));
            clientEntity.setRegistryType(clientType.getName());
        }
        clientEntityRepository.persist(clientEntity);
        return clientEntity;
    }

    @Transactional
    public void resetUser(String boxUUID, String userId) {
        resetUserInner(boxUUID, userId);
    }

    private void resetUserInner(String boxUUID, String userId) {
        // 重置用户
        userEntityRepository.deleteByUserId(boxUUID, userId);
        // 重置用户面路由
        resetNsRoute(boxUUID, userId);
        // 重置域名
        subdomainEntityRepository.deleteSubdomainByUserId(boxUUID, userId);
        // 重置client
        clientEntityRepository.deleteByUserId(boxUUID, userId);

    }

    @Transactional
    public void resetBox(String boxUUID) {
        Optional<RegistryBoxEntity> boxEntityOp = boxEntityRepository.findByBoxUUID(boxUUID);
        // 重置盒子
        boxEntityRepository.delete("box_uuid", boxUUID);
        // 重置用户
        userEntityRepository.deleteByBoxUUID(boxUUID);
        // 重置路由
        resetNsRoute(boxUUID);
        // 重置域名
        subdomainEntityRepository.deleteSubdomainByBoxUUID(boxUUID);
        // 重置client
        clientEntityRepository.deleteByBoxUUID(boxUUID);
        // 重置映射关系
        boxEntityOp.ifPresent(registryBoxEntity -> networkService.deleteByClientID(registryBoxEntity.getNetworkClientId()));

    }

    public void resetNsRoute(String boxUUID){
        var subdomainEntities = subdomainEntityRepository.findByBoxUUId(boxUUID);
        for (var subdomain: subdomainEntities
        ) {
            networkService.expireNSRoute(subdomain.getUserDomain(), 3600);
        }
    }

    public void resetNsRoute(String boxUUID, String userId){
        var subdomainEntities = subdomainEntityRepository.findByBoxUUIdAndUserId(boxUUID, userId);
        for (var subdomain: subdomainEntities
        ) {
            networkService.expireNSRoute(subdomain.getUserDomain(), 3600);
        }
    }
    /**
     * 校验boxUUID合法性
     *
     * @param boxUUID boxUUID
     */
    public void isValidBoxUUIDThrowEx(String boxUUID) {
        final boolean validBoxUUID = isValidBoxUUID(boxUUID);
        if (!validBoxUUID) {
            throw new WebApplicationException("invalid box uuid", Response.Status.FORBIDDEN);
        }
    }

    /**
     * 校验盒子是否已注册
     *
     * @param boxUUID boxUUID
     */
    public void hasBoxRegistered(String boxUUID) {
        final Optional<RegistryBoxEntity> boxEntityOp = boxEntityRepository.findByBoxUUID(boxUUID);
        if (boxEntityOp.isPresent()) {
            LOG.warnv("box uuid had already registered, boxuuid:{0}", boxUUID);
            throw new WebApplicationException("box uuid had already registered. Pls reset and try again.", Response.Status.NOT_ACCEPTABLE);
        }
    }

    /**
     * 校验盒子是否已注册 V2
     *
     * @param boxUUID boxUUID
     */
    public Boolean hasBoxRegisteredV2(String boxUUID) {
        final Optional<RegistryBoxEntity> boxEntityOp = boxEntityRepository.findByBoxUUID(boxUUID);
        return boxEntityOp.isPresent();
    }

    /**
     * 校验盒子是否未注册
     *
     * @param boxUUID   boxUUID
     * @param boxRegKey 盒子的注册码
     */
    public RegistryBoxEntity hasBoxNotRegisteredThrow(String boxUUID, String boxRegKey) {
        final Optional<RegistryBoxEntity> boxEntityOp = boxEntityRepository.findByBoxUUIDAndBoxRegKey(boxUUID, boxRegKey);
        if (boxEntityOp.isEmpty()) {
            LOG.warnv("invalid box registry info, boxUuid:{0}", boxUUID);
            throw new WebApplicationException("invalid box registry info.", Response.Status.FORBIDDEN);
        }
        return boxEntityOp.get();
    }

    /**
     * 校验盒子是否未注册 V2
     *
     * @param boxUUID boxUUID
     */
    public void hasBoxNotRegisteredThrow(String boxUUID) {
        final Optional<RegistryBoxEntity> boxEntityOp = boxEntityRepository.findByBoxUUID(boxUUID);
        if (boxEntityOp.isEmpty()) {
            LOG.warnv("invalid box registry info, boxUuid:{0}", boxUUID);
            throw new ServiceOperationException(ServiceError.BOX_NOT_REGISTERED);
        }
    }

    public Boolean hasBoxNotRegistered(String boxUUID, String boxRegKey) {
        Optional<RegistryBoxEntity> boxEntityOp = boxEntityRepository.findByBoxUUIDAndBoxRegKey(boxUUID, boxRegKey);
        return boxEntityOp.isEmpty();
    }

    /**
     * 校验subdomain是否不存在，或者已使用
     *
     * @param subdomain subdomain
     */
    public SubdomainEntity isSubdomainNotExistOrUsed(String subdomain) {
        Optional<SubdomainEntity> subdomainEntityOp = subdomainEntityRepository.findBySubdomain(subdomain);
        if (subdomainEntityOp.isEmpty()) {
            LOG.warnv("subdomain does not exist, subdomain:{0}", subdomain);
            throw new ServiceOperationException(ServiceError.SUBDOMAIN_NOT_EXIST);
        }
        if (!SubdomainStateEnum.TEMPORARY.getState().equals(subdomainEntityOp.get().getState())) {
            LOG.warnv("subdomain already used, subdomain:{0}", subdomain);
            throw new ServiceOperationException(ServiceError.SUBDOMAIN_ALREADY_USED);
        }
        return subdomainEntityOp.get();
    }

    /**
     * 校验用户是否已注册
     *
     * @param boxUUID boxUUID
     * @param userId  userId
     */
    public void hasUserRegistered(String boxUUID, String userId) {
        final List<RegistryUserEntity> userEntities = userEntityRepository.findAllByUserId(boxUUID, userId);
        if (!userEntities.isEmpty()) {
            LOG.warnv("user id had already registered, boxUUID:{0}, userId:{1}", boxUUID, userId);
            throw new WebApplicationException("user id had already registered. Pls reset and try again.", Response.Status.NOT_ACCEPTABLE);
        }
    }

    /**
     * 校验用户是否未注册
     *
     * @param boxUUID    boxUUID
     * @param userId     userId
     * @param userRegKey 用户的注册码
     */
    public void hasUserNotRegistered(String boxUUID, String userId, String userRegKey) {
        final List<RegistryUserEntity> userEntities = userEntityRepository.findAllByUserIDAndUserRegKey(boxUUID, userId, userRegKey);
        if (userEntities.isEmpty()) {
            LOG.warnv("invalid user registry info, boxUUID:{0}, userId:{1}", boxUUID, userId);
            throw new WebApplicationException("invalid user registry info.", Response.Status.FORBIDDEN);
        }
    }

    /**
     * 校验用户是否未注册 V2
     *
     * @param boxUUID boxUUID
     * @param userId  userId
     */
    public void hasUserNotRegistered(String boxUUID, String userId) {
        var registryUserEntity = userEntityRepository.findUserByBoxUUIDAndUserId(boxUUID, userId);
        if (registryUserEntity.isEmpty()) {
            LOG.warnv("invalid user registry info, boxUUID:{0}, userId:{1}", boxUUID, userId);
            throw new ServiceOperationException(ServiceError.USER_NOT_REGISTERED);
        }
    }

    /**
     * 校验client是否已注册
     *
     * @param boxUUID    boxUUID
     * @param userId     userId
     * @param clientUUID clientUUID
     */
    public void hasClientRegistered(String boxUUID, String userId, String clientUUID) {
        final List<RegistryClientEntity> clientEntities = clientEntityRepository.findAllByClientUUID(boxUUID, userId, clientUUID);
        if (!clientEntities.isEmpty()) {
            LOG.warnv("client uuid had already registered, boxUUID:{0}, userId:{1}, clientUUID:{2}", boxUUID, userId, clientUUID);
            throw new WebApplicationException("client uuid had already registered. Pls reset and try again.", Response.Status.NOT_ACCEPTABLE);
        }
    }

    public void hasClientNotRegisted(String boxUUID, String userId, String clientUUID, String clientRegKey) {
        final List<RegistryClientEntity> clientEntities = clientEntityRepository.findAllByClientUUIDAndClientRegKey(boxUUID, userId, clientUUID, clientRegKey);
        if (clientEntities.isEmpty()) {
            LOG.warnv("invalid registry client verify info, boxUuid:{0}, userId:{1}, clientUuid:{2}", boxUUID, userId, clientUUID);
            throw new WebApplicationException("invalid registry client verify info", Response.Status.FORBIDDEN);
        }
    }

    public void hasClientNotRegistered(String boxUUID, String userId, String clientUUID) {
        final List<RegistryClientEntity> clientEntities = clientEntityRepository.findAllByClientUUID(boxUUID, userId, clientUUID);
        if (clientEntities.isEmpty()) {
            LOG.warnv("invalid registry client verify info, boxUuid:{0}, userId:{1}, clientUuid:{2}", boxUUID, userId, clientUUID);
            throw new ServiceOperationException(ServiceError.CLIENT_NOT_REGISTERED);
        }
    }

    /**
     * 分发全局唯一的 subdomain，超时时间 7天
     *
     * @param boxUUID boxUUID
     * @return subdomain
     */
    public SubdomainEntity subdomainGen(String boxUUID) {
        return subdomainGen(boxUUID, MAX_EFFECTIVE_TIME);
    }

    /**
     * 分发全局唯一的 subdomain
     *
     * @param boxUUID       boxUUID
     * @param effectiveTime 有效期，单位秒
     * @return subdomainEntity
     */
    public SubdomainEntity subdomainGen(String boxUUID, Integer effectiveTime) {
        // 生成临时subdomain
        SubdomainEntity subdomainEntity;
        // 查出所有保留域名.
        while (true) {
            String subdomain = null;
            try {
                // 生成一个随机域名.
                subdomain = CommonUtils.randomLetters(1) + CommonUtils.randomDigestAndLetters(7);
                // 是保留域名则重新生成
                RegistryProvider registryProvider = providerFactory.getRegistryProvider();
                if (!registryProvider.isSubdomainIllegal(subdomain)) {
                    LOG.infov("subdomain:{0} isReservedDomain, retry...", subdomain);
                    continue;
                }
                // 尝试存储到数据库
                subdomainEntity = subdomainSave(boxUUID, subdomain, effectiveTime);
                break;
            } catch (PersistenceException exception) {
                if (exception.getCause() != null && exception.getCause().getCause() instanceof SQLIntegrityConstraintViolationException) {
                    LOG.infov("subdomain:{0} already exists, retry...", subdomain);
                } else {
                    LOG.errorv(exception, "subdomain save failed");
                    throw new ServiceOperationException(ServiceError.DATABASE_ERROR);
                }
            }
        }
        return subdomainEntity;
    }

    public SubdomainEntity subdomainSave(String boxUUID, String subdomain, Integer effectiveTime) {
        SubdomainEntity subdomainEntity = new SubdomainEntity();
        {
            subdomainEntity.setBoxUUID(boxUUID);
            subdomainEntity.setSubdomain(subdomain);
            subdomainEntity.setUserDomain(subdomain + "." + properties.getRegistrySubdomain());
            if (effectiveTime != null) {
                subdomainEntity.setExpiresAt(OffsetDateTime.now().plusSeconds(effectiveTime));
            }
            subdomainEntity.setState(SubdomainStateEnum.TEMPORARY.getState());
        }
        subdomainEntityRepository.save(subdomainEntity);
        return subdomainEntity;
    }

    public void reachUpperLimit(String boxUUID) {
        long count = subdomainEntityRepository.count("box_uuid", boxUUID);
        if (count > SUBDOMAIN_UPPER_LIMIT) {
            LOG.warnv("reach subdomain upper limit, boxUUID:{0}", boxUUID);
            throw new ServiceOperationException(ServiceError.SUBDOMAIN_UPPER_LIMIT);
        }
    }

    public Boolean networkClientAuth(String clientId, String secretKey) {
        Optional<RegistryBoxEntity> registryBoxEntityOp = boxEntityRepository.findByClientIdAndSecretKey(clientId, secretKey);
        if (registryBoxEntityOp.isEmpty()) {
            LOG.infov("network client auth failed, client id:{0}, secret key:{1}", clientId, secretKey);
        }
        return registryBoxEntityOp.isPresent();
    }

    public BoxRegistryDetailInfo boxRegistryBindUserAndClientInfo(String uuid) {
        Optional<RegistryBoxEntity> boxEntityOp = boxEntityRepository.findByBoxUUID(uuid);
        if (boxEntityOp.isEmpty()) {
            LOG.warnv("box uuid had not registered, boxuuid:{0}", uuid);
            throw new ServiceOperationException(ServiceError.BOX_NOT_REGISTERED);
        }
        var boxEntity = boxEntityOp.get();
        List<UserRegistryDetailInfo> userRegistryDetailInfos = new ArrayList<>();
        for (RegistryUserEntity userEntity : userEntityRepository.findByBoxUUId(uuid)) {
            List<ClientRegistryDetailInfo> clientRegistryDetailInfos = new ArrayList<>();
            List<RegistryClientEntity> clientEntities = clientEntityRepository.findByBoxUUIdAndUserId(uuid, userEntity.getUserId());
            clientEntities.forEach(clientEntitie -> clientRegistryDetailInfos.add(ClientRegistryDetailInfo.of(
                    clientEntitie.getRegistryType(),
                    clientEntitie.getClientUUID(),
                    clientEntitie.getCreatedAt())));
            Optional<SubdomainEntity> subdomainEntityOp = subdomainEntityRepository.findByBoxUUIDAndUserIdAndState(uuid,
                    userEntity.getUserId(), SubdomainStateEnum.USED.getState());
            userRegistryDetailInfos.add(UserRegistryDetailInfo.of(
                    subdomainEntityOp.isEmpty() ? null : subdomainEntityOp.get().getUserDomain(),
                    subdomainEntityOp.isEmpty() ? null : subdomainEntityOp.get().getSubdomain(),
                    userEntity.getRegistryType(),
                    userEntity.getUserId(),
                    userEntity.getCreatedAt(),
                    clientRegistryDetailInfos));
        }
        return BoxRegistryDetailInfo.of(boxEntity.getNetworkClientId(), boxEntity.getCreatedAt(), boxEntity.getUpdatedAt(), userRegistryDetailInfos);
    }

    /**
     * 更新域名
     *
     * @param boxUUID   boxUUID
     * @param userId    userId
     * @param subdomain subdomain
     * @return SubdomainEntity
     */
    public SubdomainUpdateResult subdomainUpdate(String boxUUID, String userId, String subdomain) {
        SubdomainUpdateResult updateResult = new SubdomainUpdateResult();
        // 合法性校验，历史域名是否已使用
        Optional<SubdomainEntity> subdomainEntityOld = subdomainEntityRepository.findByBoxUUIDAndUserIdAndState(boxUUID, userId, SubdomainStateEnum.USED.getState());
        if (subdomainEntityOld.isEmpty()) {
            LOG.warnv("user's subdomain is not in use, boxUUID:{0}, userId:{1}", boxUUID, userId);
            throw new ServiceOperationException(ServiceError.SUBDOMAIN_NOT_IN_USER);
        }
        String subdomainOld = subdomainEntityOld.get().getSubdomain();
        // 唯一性校验 & 幂等设计，防止数据库、redis数据不一致，建议client失败重试3次
        Optional<SubdomainEntity> subdomainEntityOp = subdomainEntityRepository.findBySubdomain(subdomain);
        if (subdomainEntityOp.isPresent() && !subdomainOld.equals(subdomain)) {
            LOG.warnv("subdomain already exist, subdomain:{0}", subdomain);
            updateResult.setSuccess(false);
            updateResult.setCode(ServiceError.SUBDOMAIN_ALREADY_EXIST.getCode());
            updateResult.setError(ServiceError.SUBDOMAIN_ALREADY_EXIST.getMessage());
            List<String> recommends = new ArrayList<>();
            recommendSubdomains(subdomain, recommends);
            updateResult.setRecommends(recommends);
            return updateResult;
        }
        // 黑名单校验
        RegistryProvider registryProvider = providerFactory.getRegistryProvider();
        if (!registryProvider.isSubdomainIllegal(subdomain)) {
            LOG.infov("subdomain:{0} is reserved", subdomain);
            updateResult.setSuccess(false);
            updateResult.setCode(ServiceError.SUBDOMAIN_IS_RESERVED.getCode());
            updateResult.setError(ServiceError.SUBDOMAIN_IS_RESERVED.getMessage());
            updateResult.setRecommends(new ArrayList<>());
            return updateResult;
        }

        LOG.infov("subdomain update begin, from:{0} to:{1}", subdomainOld, subdomain);
        // 更新域名
        String userDomain = subdomain + "." + properties.getRegistrySubdomain();
        if (!subdomainOld.equals(subdomain)) {
            subdomainService.updateSubdomain(boxUUID, userId, subdomain, userDomain, subdomainOld);
        }
        // 添加用户面路由：用户域名 - network server 地址 & network client id
        networkService.cacheNSRoute(userDomain, boxUUID);
        LOG.infov("subdomain update succeed, from:{0} to:{1}", subdomainOld, subdomain);

        updateResult.setSuccess(true);
        updateResult.setBoxUUID(boxUUID);
        updateResult.setUserId(userId);
        updateResult.setSubdomain(subdomain);
        return updateResult;
    }

    /**
     * 域名推荐规则：
     * <p>
     * 1.补年份（如2022）、月份+日期（如0330）；
     * 2.补2~3位字母（从a ~ z），字母随机；
     * 3.从已输入的末位开始扣，直到扣到6位；
     * 4.若可供推荐的不够5个，就从第6位开始，按照前2个规则补；
     */
    private void recommendSubdomains(String subdomain, List<String> recommends) {
        recommendYear(subdomain, recommends);
        recommendDate(subdomain, recommends);
        // 补2~3位字母（从a ~ z），字母随机；
        recommendRandomNum(subdomain, recommends);
        // 从已输入的末位开始扣，直到扣到6位；
        recommendSquashNum(subdomain, recommends);
        // 若可供推荐的不够5个，就从第6位开始，按照前2个规则补；
        subdomain = subdomain.substring(0, RECOMMEND_SUBDOMAIN_MIN_LENGTH);
        recommendYear(subdomain, recommends);
        recommendDate(subdomain, recommends);
        recommendRandomNum(subdomain, recommends);
    }

    private void recommendYear(String subdomain, List<String> recommends) {
        if (recommends.size() >= RECOMMEND_SUBDOMAIN_UPPER_LIMIT) {
            return;
        }
        String recommend = recommendYear(subdomain);
        if (isSubdomainIllegal(recommend)) {
            recommends.add(recommend);
        }
    }

    private void recommendDate(String subdomain, List<String> recommends) {
        if (recommends.size() >= RECOMMEND_SUBDOMAIN_UPPER_LIMIT) {
            return;
        }
        String recommend = recommendDate(subdomain);
        if (isSubdomainIllegal(recommend)) {
            recommends.add(recommend);
        }
    }

    private void recommendRandomNum(String subdomain, List<String> recommends) {
        if (recommends.size() >= RECOMMEND_SUBDOMAIN_UPPER_LIMIT) {
            return;
        }
        for (int i = 0; i < RECOMMEND_SUBDOMAIN_RETRYS; i++) {
            String recommend = recommendRandomNum(subdomain);
            if (!isSubdomainIllegal(recommend)) {
                continue;
            }
            recommends.add(recommend);
            if (recommends.size() >= RECOMMEND_SUBDOMAIN_UPPER_LIMIT) {
                return;
            }
        }
    }

    private void recommendSquashNum(String subdomain, List<String> recommends) {
        if (recommends.size() >= RECOMMEND_SUBDOMAIN_UPPER_LIMIT) {
            return;
        }
        while (subdomain.length() > RECOMMEND_SUBDOMAIN_MIN_LENGTH) {
            subdomain = recommendSquashNum(subdomain);
            if (!isSubdomainIllegal(subdomain)) {
                continue;
            }
            recommends.add(subdomain);
            if (recommends.size() >= RECOMMEND_SUBDOMAIN_UPPER_LIMIT) {
                return;
            }
        }
    }

    private String recommendYear(String subdomain) {
        OffsetDateTime dateTime = OffsetDateTime.now();
        return subdomain + dateTime.getYear();
    }

    private String recommendDate(String subdomain) {
        OffsetDateTime dateTime = OffsetDateTime.now();
        return subdomain + dateTime.format(DateTimeFormatter.ofPattern("MMdd"));
    }

    private String recommendRandomNum(String subdomain) {
        if (random.nextBoolean()) {
            String randomAB = CommonUtils.randomLetters(2);
            return subdomain + randomAB;
        } else {
            String randomABC = CommonUtils.randomLetters(3);
            return subdomain + randomABC;
        }
    }

    private String recommendSquashNum(String subdomain) {
        return subdomain.substring(0, subdomain.length() - 1);
    }

    private Boolean isSubdomainIllegal(String subdomain) {
        if (subdomain.length() > RECOMMEND_SUBDOMAIN_MAX_LENGTH) {
            return false;
        }
        Optional<SubdomainEntity> subdomainEntityOp = subdomainEntityRepository.findBySubdomain(subdomain);
        if (subdomainEntityOp.isPresent()) {
            LOG.debugv("subdomain already exist, subdomain:{0}", subdomain);
            return false;
        }
        RegistryProvider registryProvider = providerFactory.getRegistryProvider();
        return registryProvider.isSubdomainIllegal(subdomain);
    }

    public Set<String> getAdminBindClients() {
        Set<String> clientUUIDs = new HashSet<>();
        // 批量查询admin用户
        PanacheQuery<RegistryUserEntity> userEntityPanacheQuery = userEntityRepository.findAllByType(RegistryTypeEnum.USER_ADMIN.getName(),
                BEGIN_INDEX, PAGE_SIZE);
        while (userEntityPanacheQuery.stream().findAny().isPresent()) {
            List<Map<String, String>> userIds = new ArrayList<>();
            Set<String> boxUUIDs = new HashSet<>();
            userEntityPanacheQuery.stream().forEach(k -> {
                Map<String, String> userId = new HashMap<>();
                userId.put(k.getBoxUUID(), k.getUserId());
                userIds.add(userId);
                boxUUIDs.add(k.getBoxUUID());
            });
            // 批量查询bind客户端
            List<RegistryClientEntity> clientEntities = clientEntityRepository.findByBoxUUIDsAndType(boxUUIDs, RegistryTypeEnum.CLIENT_BIND.getName());
            List<String> bindUUIDs = clientEntities.stream()
                    .filter(k -> {
                        Map<String, String> userId = new HashMap<>();
                        userId.put(k.getBoxUUID(), k.getUserId());
                        return userIds.contains(userId);
                    })
                    .map(RegistryClientEntity::getClientUUID)
                    .collect(Collectors.toList());
            clientUUIDs.addAll(bindUUIDs);
            userEntityPanacheQuery = userEntityPanacheQuery.nextPage();
        }

        return clientUUIDs;
    }

    public RegistryUserEntity getRegistryUserEntity(String boxUUID, String userId){
        var registryUserEntity = userEntityRepository.findUserByBoxUUIDAndUserId(
            boxUUID, userId);
        return registryUserEntity.orElse(null);
    }

    public SubdomainEntity getSubdomainEntity(String boxUUID, String userId){
        var subdomainEntity = subdomainEntityRepository.findByBoxUUIDAndUserIdAndState(
            boxUUID, userId, 1);
        return subdomainEntity.orElse(null);
    }
    public RegistryClientEntity getRegistryClientEntity(String boxUUID, String userId, String clientUUID){
        var registryClientEntity = clientEntityRepository.findByBoxUUIDAndUserIdAndClientUUID(boxUUID, userId, clientUUID);
        return registryClientEntity.orElse(null);
    }
}
