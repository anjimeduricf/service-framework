package org.trips.service_framework.utils;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import org.trips.service_framework.constants.CmsConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


public class CmsUtils {

    public static String sanitizeInput(String attribute, String value) {
        if (StringUtils.isEmpty(value) || CmsConstants.NOT_APPLICABLE.equals(value)) {
            return CmsConstants.NOT_APPLICABLE;
        }

        if (attribute.equals("treatment")) {
            return sanitizeTreatment(value);
        }

        return value.trim();
    }

    private static String sanitizeTreatment(String treatment) {
        List<String> treatmentArray = new ArrayList<>();
        Arrays.stream(treatment.split(","))
                .map(CmsUtils::reformatTreatmentString)
                .filter(trt -> StringUtils.isNotEmpty(trt))
                .forEach(trt -> treatmentArray.add(String.format("\"%s\"", trt)));

        if (CollectionUtils.isEmpty(treatmentArray)) {
            return CmsConstants.NOT_APPLICABLE;
        }

        String result = StringUtils.join(treatmentArray, ",");
        return String.format("[%s]", result);
    }

    private static String reformatTreatmentString(String treatment) {
        treatment = treatment.replace("[", "").replace("]", "");
        treatment = treatment.replaceAll("\"", "");
        return treatment.trim();
    }
}
