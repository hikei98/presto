/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.elasticsearch;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Closer;
import io.airlift.tpch.TpchTable;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.testing.MaterializedRow;
import io.prestosql.testing.QueryRunner;
import io.prestosql.tests.AbstractTestIntegrationSmokeTest;
import org.elasticsearch.common.xcontent.XContentType;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

import static io.prestosql.elasticsearch.ElasticsearchQueryRunner.createElasticsearchQueryRunner;
import static io.prestosql.elasticsearch.EmbeddedElasticsearchNode.createEmbeddedElasticsearchNode;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.testing.MaterializedResult.resultBuilder;
import static io.prestosql.testing.assertions.Assert.assertEquals;
import static java.lang.String.format;
import static org.elasticsearch.client.Requests.refreshRequest;

public class TestElasticsearchIntegrationSmokeTest
        extends AbstractTestIntegrationSmokeTest
{
    private final EmbeddedElasticsearchNode embeddedElasticsearchNode;

    private QueryRunner queryRunner;

    public TestElasticsearchIntegrationSmokeTest()
    {
        this(createEmbeddedElasticsearchNode());
    }

    public TestElasticsearchIntegrationSmokeTest(EmbeddedElasticsearchNode embeddedElasticsearchNode)
    {
        super(() -> createElasticsearchQueryRunner(embeddedElasticsearchNode, TpchTable.getTables()));
        this.embeddedElasticsearchNode = embeddedElasticsearchNode;
    }

    @BeforeClass
    public void setUp()
    {
        queryRunner = getQueryRunner();
    }

    @AfterClass(alwaysRun = true)
    public final void destroy()
            throws IOException
    {
        try (Closer closer = Closer.create()) {
            closer.register(queryRunner);
            closer.register(embeddedElasticsearchNode);
        }
        queryRunner = null;
    }

    @Test
    @Override
    public void testSelectAll()
    {
        // List columns explicitly, as there's no defined order in Elasticsearch
        assertQuery("SELECT orderkey, custkey, orderstatus, totalprice, orderdate, orderpriority, clerk, shippriority, comment  FROM orders");
    }

    @Test
    @Override
    public void testDescribeTable()
    {
        MaterializedResult actualColumns = computeActual("DESC orders").toTestTypes();
        MaterializedResult.Builder builder = resultBuilder(getQueryRunner().getDefaultSession(), VARCHAR, VARCHAR, VARCHAR, VARCHAR);
        for (MaterializedRow row : actualColumns.getMaterializedRows()) {
            builder.row(row.getField(0), row.getField(1), "", "");
        }
        MaterializedResult actualResult = builder.build();
        builder = resultBuilder(getQueryRunner().getDefaultSession(), VARCHAR, VARCHAR, VARCHAR, VARCHAR);
        MaterializedResult expectedColumns = builder
                .row("clerk", "varchar", "", "")
                .row("comment", "varchar", "", "")
                .row("custkey", "bigint", "", "")
                .row("orderdate", "timestamp", "", "")
                .row("orderkey", "bigint", "", "")
                .row("orderpriority", "varchar", "", "")
                .row("orderstatus", "varchar", "", "")
                .row("shippriority", "bigint", "", "")
                .row("totalprice", "real", "", "")
                .build();
        assertEquals(actualResult, expectedColumns, format("%s != %s", actualResult, expectedColumns));
    }

    @Test
    public void testNestedFields()
    {
        String indexName = "data";
        index(indexName, ImmutableMap.<String, Object>builder()
                .put("name", "nestfield")
                .put("fields.fielda", 32)
                .put("fields.fieldb", "valueb")
                .build());

        embeddedElasticsearchNode.getClient()
                .admin()
                .indices()
                .refresh(refreshRequest(indexName))
                .actionGet();

        assertQuery(
                "SELECT name, fields.fielda, fields.fieldb FROM data",
                "VALUES ('nestfield', 32, 'valueb')");
    }

    @Test
    public void testNestedVariants()
    {
        String indexName = "nested_variants";

        index(indexName,
                ImmutableMap.of("a",
                        ImmutableMap.of("b",
                                ImmutableMap.of("c",
                                        "value1"))));

        index(indexName,
                ImmutableMap.of("a.b",
                        ImmutableMap.of("c",
                                "value2")));

        index(indexName,
                ImmutableMap.of("a",
                        ImmutableMap.of("b.c",
                                "value3")));

        index(indexName,
                ImmutableMap.of("a.b.c", "value4"));

        embeddedElasticsearchNode.getClient()
                .admin()
                .indices()
                .refresh(refreshRequest(indexName))
                .actionGet();

        assertQuery(
                "SELECT a.b.c FROM nested_variants",
                "VALUES 'value1', 'value2', 'value3', 'value4'");
    }

    @Test
    public void testDataTypes()
    {
        String indexName = "types";

        embeddedElasticsearchNode.getClient()
                .admin()
                .indices()
                .prepareCreate(indexName)
                .addMapping("doc",
                        "boolean_column", "type=boolean",
                        "float_column", "type=float",
                        "double_column", "type=double",
                        "integer_column", "type=integer",
                        "long_column", "type=long",
                        "keyword_column", "type=keyword",
                        "text_column", "type=text",
                        "binary_column", "type=binary",
                        "timestamp_column", "type=date")
                .get();

        index(indexName, ImmutableMap.<String, Object>builder()
                .put("boolean_column", true)
                .put("float_column", 1.0f)
                .put("double_column", 1.0d)
                .put("integer_column", 1)
                .put("long_column", 1L)
                .put("keyword_column", "cool")
                .put("text_column", "some text")
                .put("binary_column", new byte[] {(byte) 0xCA, (byte) 0xFE})
                .put("timestamp_column", 0)
                .build());

        embeddedElasticsearchNode.getClient()
                .admin()
                .indices()
                .refresh(refreshRequest(indexName))
                .actionGet();

        MaterializedResult rows = computeActual("" +
                "SELECT " +
                "boolean_column, " +
                "float_column, " +
                "double_column, " +
                "integer_column, " +
                "long_column, " +
                "keyword_column, " +
                "text_column, " +
                "binary_column, " +
                "timestamp_column " +
                "FROM types");

        MaterializedResult expected = resultBuilder(getSession(), rows.getTypes())
                .row(true, 1.0f, 1.0d, 1, 1L, "cool", "some text", new byte[] {(byte) 0xCA, (byte) 0xFE}, LocalDateTime.of(1970, 1, 1, 0, 0))
                .build();

        assertEquals(rows.getMaterializedRows(), expected.getMaterializedRows());
    }

    @Test
    public void testDataTypesNested()
    {
        String indexName = "types_nested";

        embeddedElasticsearchNode.getClient()
                .admin()
                .indices()
                .prepareCreate(indexName)
                .addMapping("doc", "" +
                                "{ " +
                                "    \"properties\": {\n" +
                                "        \"field\": {\n" +
                                "            \"properties\": {\n" +
                                "                \"boolean_column\":   { \"type\": \"boolean\" },\n" +
                                "                \"float_column\":     { \"type\": \"float\" },\n" +
                                "                \"double_column\":    { \"type\": \"double\" },\n" +
                                "                \"integer_column\":   { \"type\": \"integer\" },\n" +
                                "                \"long_column\":      { \"type\": \"long\" },\n" +
                                "                \"keyword_column\":   { \"type\": \"keyword\" },\n" +
                                "                \"text_column\":      { \"type\": \"text\" },\n" +
                                "                \"binary_column\":    { \"type\": \"binary\" },\n" +
                                "                \"timestamp_column\": { \"type\": \"date\" }\n" +
                                "            }\n" +
                                "        }\n" +
                                "    }" +
                                "}\n",
                        XContentType.JSON)
                .get();

        index(indexName, ImmutableMap.of(
                "field",
                ImmutableMap.<String, Object>builder()
                        .put("boolean_column", true)
                        .put("float_column", 1.0f)
                        .put("double_column", 1.0d)
                        .put("integer_column", 1)
                        .put("long_column", 1L)
                        .put("keyword_column", "cool")
                        .put("text_column", "some text")
                        .put("binary_column", new byte[] {(byte) 0xCA, (byte) 0xFE})
                        .put("timestamp_column", 0)
                        .build()));

        embeddedElasticsearchNode.getClient()
                .admin()
                .indices()
                .refresh(refreshRequest(indexName))
                .actionGet();

        MaterializedResult rows = computeActual("" +
                "SELECT " +
                "field.boolean_column, " +
                "field.float_column, " +
                "field.double_column, " +
                "field.integer_column, " +
                "field.long_column, " +
                "field.keyword_column, " +
                "field.text_column, " +
                "field.binary_column, " +
                "field.timestamp_column " +
                "FROM types_nested");

        MaterializedResult expected = resultBuilder(getSession(), rows.getTypes())
                .row(true, 1.0f, 1.0d, 1, 1L, "cool", "some text", new byte[] {(byte) 0xCA, (byte) 0xFE}, LocalDateTime.of(1970, 1, 1, 0, 0))
                .build();

        assertEquals(rows.getMaterializedRows(), expected.getMaterializedRows());
    }

    @Test
    public void testQueryString()
    {
        MaterializedResult actual = computeActual("SELECT count(*) FROM \"orders: +packages -slyly\"");

        MaterializedResult expected = resultBuilder(getSession(), ImmutableList.of(BIGINT))
                .row(1639L)
                .build();

        assertEquals(actual, expected);
    }

    @Test
    public void testMixedCase()
    {
        String indexName = "mixed_case";
        index(indexName, ImmutableMap.<String, Object>builder()
                .put("Name", "john")
                .put("AGE", 32)
                .build());

        embeddedElasticsearchNode.getClient()
                .admin()
                .indices()
                .refresh(refreshRequest(indexName))
                .actionGet();

        assertQuery(
                "SELECT name, age FROM mixed_case",
                "VALUES ('john', 32)");

        assertQuery(
                "SELECT name, age FROM mixed_case WHERE name = 'john'",
                "VALUES ('john', 32)");
    }

    @Test
    public void testQueryStringError()
    {
        assertQueryFails("SELECT count(*) FROM \"orders: ++foo AND\"", "\\QFailed to parse query [ ++foo and]\\E");
    }

    private void index(String indexName, Map<String, Object> document)
    {
        embeddedElasticsearchNode.getClient()
                .prepareIndex(indexName, "doc")
                .setSource(document)
                .get();
    }
}
