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
import org.trips.service_framework.heplers.CmsHelper;
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

    public CmsSkuResponse skuSearchHelper(GraphQLRequest searchSkus) {
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

        log.info("Searching for SKU by code from CMS, Payload: {}", requestParams);
        GraphQLRequest searchSkus = GraphQLRequest.builder()
                .operationName("SearchSkuByCode")
                .resource(gqlUtils.getQueryFilePath("searchSkuByCode.graphql"))
                .variables(requestParams)
                .header("saas-namespace", cmsNamespaceId)
                .build();

        CmsSkuResponse response = skuSearchHelper(searchSkus);

        if (Objects.isNull(response) || Objects.isNull(response.getData()) || response.getData().getSearchSkus().isEmpty()) {
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

    public CmsSkuResponse getSkuByAttributes(SkuAttributes attributes) {
        Map<String, Object> requestParams = cmsHelper.getSearchQueryFromAttributes(attributes);

        log.info("Searching for SKU by attributes from CMS, Payload: {}", requestParams);
        GraphQLRequest searchSkus = GraphQLRequest.builder()
                .operationName("SearchSkus")
                .resource(gqlUtils.getQueryFilePath("searchSku.graphql"))
                .variables(requestParams)
                .header("saas-namespace", cmsNamespaceId)
                .build();

        CmsSkuResponse response = skuSearchHelper(searchSkus);

        if (Objects.isNull(response) || Objects.isNull(response.getData())) {
            throw new CmsException(String.format("SKU search by attribute returned null response. Search attributes: %s", requestParams));
        }

        if (response.getData().getSearchSkus().isEmpty()) {
            log.error("No SKU found for the given sku attributes");
            throw new CmsException(String.format("SKU search returned no SKUs. Search attributes: %s", requestParams));
        }

        return response;
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
            CmsSkuResponse response = getSkuByAttributes(attributes);
            return response.getData().getSearchSkus().get(0);
        } catch (CmsException cmsException) {
            log.info("SKU not found. Creating new SKU for attributes {}", attributes);
            return createSku(attributes);
        }
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
