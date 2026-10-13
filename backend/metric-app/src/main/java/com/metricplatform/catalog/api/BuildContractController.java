package com.metricplatform.catalog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 使用打包的同一 OpenAPI 供部署前核对，不读取本机源码目录。 */
@RestController
@RequestMapping(BuildContractController.ROOT)
public class BuildContractController {
    public static final String ROOT = "/api/dbt/contract";
    public static final String PROTOCOL_VERSION = "protocolVersion";

    private static final String CONTRACT = "/contract/openapi.yaml", VERSION = "execution-snapshot";

    /** 随应用打包的公开接口契约。 */
    private final Map<String, Object> document;

    public BuildContractController() {
        try (var stream = getClass().getResourceAsStream(CONTRACT)) {
            if (stream == null) throw new IllegalStateException("公开契约未打包");
            document = new ObjectMapper(new YAMLFactory()).readValue(stream, Map.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("公开契约无效", e);
        }
    }

    @GetMapping
    public Map<String, Object> contract() {
        var result = new LinkedHashMap<>(document);
        result.put(BuildContractController.PROTOCOL_VERSION, VERSION);
        return result;
    }
}
