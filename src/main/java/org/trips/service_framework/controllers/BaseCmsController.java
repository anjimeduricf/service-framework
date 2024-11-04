package org.trips.service_framework.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.trips.service_framework.aop.Authenticate;
import org.trips.service_framework.dtos.CmsSearchRequestBody;
import org.trips.service_framework.dtos.CmsSkuResponse.Sku;
import org.trips.service_framework.dtos.SkuAttributes;
import org.trips.service_framework.helpers.CmsHelper;
import org.trips.service_framework.models.responses.BaseResponse;
import org.trips.service_framework.services.CmsService;

import java.util.*;

@RequiredArgsConstructor
public abstract class BaseCmsController<R extends BaseResponse> {
    protected final CmsService cmsService;
    protected final CmsHelper cmsHelper;

    protected abstract R createResponse(List<Sku> skuList);

    @Authenticate
    @RequestMapping(value = "/search", method = RequestMethod.POST)
    public R skuSearch(@RequestBody CmsSearchRequestBody body) {
        return createResponse(cmsService.skuSearch(body));
    }

    @Authenticate
    @RequestMapping(value = "/find-or-create", method = RequestMethod.POST)
    public R lookupOrCreateSku(@RequestBody SkuAttributes body) {
        return createResponse(Collections.singletonList(cmsService.getOrCreateSku(body)));
    }
}
