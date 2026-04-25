package com.example.epub.api.catalog;

import com.example.epub.api.dto.catalog.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class CatalogInputTest {
    @Test void optionalFieldsCanBeOmittedAndUnknownMutationFieldsAreRejected() {
        var mapper=JsonMapper.builder().build();
        assertNotNull(mapper.readValue("{\"name\":\"Books\"}",CategoryInput.class));
        assertNotNull(mapper.readValue("{\"title\":\"Book\",\"authorDisplay\":\"Author\"}",BookInput.class));
        assertThrows(Exception.class,() -> mapper.readValue("{\"title\":\"Book\",\"authorDisplay\":\"Author\",\"status\":\"PUBLISHED\"}",BookInput.class));
    }
}
