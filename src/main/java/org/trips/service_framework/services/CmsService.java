package org.trips.service_framework.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import graphql.kickstart.spring.webclient.boot.GraphQLRequest;
import graphql.kickstart.spring.webclient.boot.GraphQLResponse;
import graphql.kickstart.spring.webclient.boot.GraphQLWebClient;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.trips.service_framework.constants.CmsConstants;
import org.trips.service_framework.dtos.CmsSkuResponse;
import org.trips.service_framework.dtos.CmsSkuResponse.Sku;
import org.trips.service_framework.dtos.SkuAttributes;
import org.trips.service_framework.events.SnsEventPublisher;
import org.trips.service_framework.events.dto.SkuCreationTopicMessage;
import org.trips.service_framework.exceptions.CmsException;
import org.trips.service_framework.exceptions.NotAllowedException;
import org.trips.service_framework.exceptions.NotFoundException;
import org.trips.service_framework.helpers.CmsHelper;
import org.trips.service_framework.utils.GraphQLUtils;
import org.trips.service_framework.utils.ValidationUtils;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CmsService {

    @Value("${cms.namespace-id}")
    private String cmsNamespaceId;

    private final String cmsUrl;
    private final CmsHelper cmsHelper;
    private final GraphQLUtils gqlUtils;
    private final ObjectMapper objectMapper;
    private final GraphQLWebClient graphQLClient;
    private final SnsEventPublisher snsEventPublisher;

    public CmsService(
            @Value("${cms.base-url}") String cmsUrl,
            GraphQLUtils gqlUtils,
            ObjectMapper objectMapper,
            CmsHelper cmsHelper,
            SnsEventPublisher snsEventPublisher
    ) {
        ConnectionProvider provider = ConnectionProvider.builder("fixed")
                .maxConnections(500)
                .maxIdleTime(Duration.ofSeconds(20))
                .maxLifeTime(Duration.ofSeconds(60))
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(120))
                .build();

        WebClient webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create(provider)))
                .baseUrl(cmsUrl)
                .build();

        this.cmsUrl = cmsUrl;
        this.gqlUtils = gqlUtils;
        this.cmsHelper = cmsHelper;
        this.objectMapper = objectMapper;
        this.snsEventPublisher = snsEventPublisher;
        this.graphQLClient = GraphQLWebClient.newInstance(webClient, this.objectMapper);
    }

    public CmsSkuResponse skuSearchHelper(String operationName, String resourcePath, Map<String, Object> requestParams) {
        GraphQLRequest searchSkus = GraphQLRequest.builder()
                .operationName(operationName)
                .resource(gqlUtils.getQueryFilePath(resourcePath))
                .variables(requestParams)
                .header("saas-namespace", cmsNamespaceId)
                .build();

        log.info("Search sku request header: {}", searchSkus.getHeaders());

        GraphQLResponse skuResponse = graphQLClient.post(searchSkus).block();
        if (Objects.isNull(skuResponse)) {
            throw new CmsException("GraphQl search sku response is null");
        }

        List<Sku> skuList = skuResponse.getList("searchSkus", Sku.class);
        CmsSkuResponse response = CmsSkuResponse
                .builder()
                .data(CmsSkuResponse.ResponseBody.builder().searchSkus(skuList).build())
                .build();

        return response;
    }

    public List<Sku> getSkuByCodes(List<String> codes) {
        Set<String> uniqueSkuCodes = Sets.newHashSet(codes);
        Map<String, Object> requestParams = cmsHelper.getSearchQueryFromSkuCodes(uniqueSkuCodes);

        log.info("Searching for SKUs by code from CMS, Payload: {}", requestParams);
        CmsSkuResponse response = skuSearchHelper("SearchSkuByCode", "searchSkuByCode.graphql", requestParams);

        if (Objects.isNull(response) || Objects.isNull(response.getData()) || CollectionUtils.isEmpty(response.getData().getSearchSkus())) {
            throw new CmsException(String.format("SKU search by codes returned null or empty response. Search codes: %s", codes));
        }

        List<Sku> skus = response.getData().getSearchSkus();
        Set<String> responseSkuList = skus.stream().map(Sku::getCode).collect(Collectors.toSet());
        Sets.SetView<String> lostSkus = Sets.difference(uniqueSkuCodes, responseSkuList);

        if (!lostSkus.isEmpty()) {
            throw new NotAllowedException(String.format("SKUs with these codes not found: %s", lostSkus));
        }

        return skus;
    }

    // This method returns an exact SKU based on the attributes provided.
    public Sku getSkuByAttributes(SkuAttributes attributes) {
        Map<String, Object> requestParams = cmsHelper.getSearchQueryFromAttributes(attributes);

        log.info("Searching for SKU by attributes from CMS, Payload: {}", requestParams);
        CmsSkuResponse response = skuSearchHelper("SearchSkus", "searchSku.graphql", requestParams);

        if (Objects.isNull(response) || Objects.isNull(response.getData())) {
            throw new CmsException("SKU search by attributes returned a null response");
        }

        if (CollectionUtils.isEmpty(response.getData().getSearchSkus())) {
            throw new CmsException("No SKU found for the given sku attributes");
        }

        if (response.getData().searchSkus.size() > 1) {
            log.error("Multiple SKUs found for the given sku attributes");
            throw new CmsException("Multiple SKUs found for the given sku attributes");
        }

        return response.getData().searchSkus.get(0);
    }

    public Sku createSku(SkuAttributes skuAttributes) {
        ValidationUtils.validate(skuAttributes);
        Map<String, Object> requestParams = cmsHelper.getSearchQueryFromAttributes(skuAttributes);
        List<Map<String, String>> attributes = (List) ((Map) requestParams.get("searchQuery")).get("filters");
        Map<String, String> skuAttributesMap = attributes.stream()
                .collect(Collectors.toMap(attribute -> attribute.get("name"), attribute -> attribute.get("value")));

        skuAttributesMap.put("version", "v2");

        List<Map<String, String>> skuCreationAttributes = skuAttributesMap.entrySet()
                .stream().map(x -> Map.of("name", x.getKey(), "value", x.getValue())).collect(Collectors.toList());

        Map<String, Object> skuData = new HashMap<>();
        skuData.put("attributes", skuCreationAttributes);
        skuData.put("productType", CmsConstants.PRODUCT_TYPE);
        skuData.put("name", cmsHelper.constructSkuName(skuCreationAttributes));

        Map<String, Object> requestData = new HashMap<>();
        requestData.put("sku", skuData);

        GraphQLRequest createSku = GraphQLRequest.builder()
                .operationName("CreateSKU")
                .resource(gqlUtils.getQueryFilePath("createSku.graphql"))
                .variables(requestData)
                .header("saas-namespace", cmsNamespaceId)
                .build();

        log.info("Create sku headers: {}", createSku.getHeaders());

        GraphQLResponse skuResponse = graphQLClient.post(createSku).block();

        Sku sku = Optional.ofNullable(skuResponse)
                .map(x -> x.getFirst(Sku.class))
                .orElseThrow(() -> new NotFoundException("No SKU found in SKU creation response"));

        SkuCreationTopicMessage skuCreationTopicMessage = SkuCreationTopicMessage.of(sku.getCode());
        log.info("SNS message body for SKU {}: {}", sku.getCode(), skuCreationTopicMessage);

        String responseMessageId = snsEventPublisher.publishToSkuCreationTopic(skuCreationTopicMessage);
        log.info("SNS message published for SKU {}, message ID: {}", sku.getCode(), responseMessageId);

        return sku;
    }

    public Sku getOrCreateSku(SkuAttributes attributes) {
        try {
            return getSkuByAttributes(attributes);
        } catch (CmsException cmsException) {
            log.info("SKU not found. Creating new SKU for attributes {}", attributes);
            return createSku(attributes);
        }
    }

    // This method returns a list of alike SKUs based on the attributes provided.
    public List<Sku> getSkusByAttributes(SkuAttributes attributes, boolean getLiteResponse) {
        Map<String, Object> requestParams = cmsHelper.getQueryForSkusSearch(attributes);

        log.info("Searching for SKU(s) by attributes from CMS, Payload: {}", requestParams);
        String resourcePath = getLiteResponse ? "searchSkuLite.graphql" : "searchSku.graphql";
        CmsSkuResponse response = skuSearchHelper("SearchSkus", resourcePath, requestParams);

        if (Objects.isNull(response) || Objects.isNull(response.getData())) {
            throw new CmsException("SKU search by attributes returned null response");
        }

        if (CollectionUtils.isEmpty(response.getData().getSearchSkus())) {
            throw new CmsException("No SKU found for the given sku attributes");
        }

        return response.getData().getSearchSkus();
    }

    public List<Sku> skuSearch(Map<String, String> body) {
        String searchType = Optional.ofNullable(body.get("search_type"))
                .orElseThrow(() -> new RuntimeException("Search type is required for search!"));

        body.remove("search_type");

        switch (searchType) {
            case "SEARCH_BY_CODES":
                return searchSkusByCodes(body);
            case "SEARCH_BY_ATTRIBUTES":
                return Collections.singletonList(getSkuByAttributes(cmsHelper.getAttributesFromMap(body)));
            case "SEARCH_ALIKE_SKUS":
                return searchAlikeSkus(body);
            default:
                throw new IllegalArgumentException("Invalid search type: " + searchType);
        }
    }

    private List<Sku> searchSkusByCodes(Map<String, String> body) {
        String codes = Optional.ofNullable(body.get("codes"))
                .orElseThrow(() -> new RuntimeException("SKU Codes are required for search!"));

        return getSkuByCodes(Arrays.stream(codes.split(","))
                .map(String::trim)
                .collect(Collectors.toList()));
    }

    private List<Sku> searchAlikeSkus(Map<String, String> body) {
        boolean getLiteResponse = Optional.ofNullable(body.get("get_lite_response"))
                .map(Boolean::parseBoolean)
                .orElse(false);

        return getSkusByAttributes(cmsHelper.getAttributesFromMap(body), getLiteResponse);
    }

    public Date fetchSkuExpiryDate(String skuCode, DateTime createdAt) {
        log.info("CMS: fetching expiry date for skuCode: {}, createdAt: {}", skuCode, createdAt);
        DateTime referenceTime = Objects.nonNull(createdAt) ? createdAt : DateTime.now();
        Date defaultShelfLife = referenceTime.plusYears(2).toDate();

        Sku sku = getSkuByCodes(List.of(skuCode)).get(0);

        if (sku.getShelfLife().isEmpty()) {
            log.info("CMS: received empty shelfLife for skuCode: {}, using defaultShelfLife: {}", skuCode, defaultShelfLife);
            return defaultShelfLife;
        }

        try {
            int shelfLife = Integer.parseInt(sku.getShelfLife().get());
            log.info("CMS: received shelfLife: {} for skuCode: {}, expiryDate is: {}", shelfLife, skuCode, referenceTime.plusDays(shelfLife).toDate());
            return referenceTime.plusDays(shelfLife).toDate();
        } catch (NumberFormatException e) {
            log.warn("Invalid shelf life found from cms for sku code {}", skuCode);
            return defaultShelfLife;
        }
    }

}
