package com.metricplatform.metadata;

import static com.metricplatform.metadata.MetadataDtos.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.mapper.DatasetFieldMapper;
import com.metricplatform.semantic.SemanticDefinitionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class MetadataController {
    private final MetadataService service;
    private final SemanticDefinitionService semantic;
    private final DatasetFieldMapper fields;

    public MetadataController(
            MetadataService s, SemanticDefinitionService x, DatasetFieldMapper f) {
        service = s;
        semantic = x;
        fields = f;
    }

    @PostMapping("/datasets")
    public ResponseEntity<DatasetEntity> createDataset(
            @Valid @RequestBody CreateDatasetRequest request, Authentication authentication) {
        return ResponseEntity.status(201).body(service.createDataset(request, authentication));
    }

    @GetMapping("/datasets/{id}/fields")
    public List<DatasetFieldEntity> fields(@PathVariable UUID id) {
        return fields.selectList(
                new LambdaQueryWrapper<DatasetFieldEntity>()
                        .eq(DatasetFieldEntity::getDatasetId, id));
    }

    @PostMapping("/dimensions")
    public ResponseEntity<Object> dimension(@Valid @RequestBody DimensionRequest request) {
        return ResponseEntity.status(201)
                .body(semantic.createDimension(request.datasetId(), request));
    }

    @PostMapping("/metrics")
    public ResponseEntity<Object> metric(@Valid @RequestBody MetricRequest request) {
        return ResponseEntity.status(201).body(semantic.createMetric(request.datasetId(), request));
    }

    @GetMapping("/datasets")
    public List<DatasetEntity> datasets() {
        return service.datasets();
    }
}
