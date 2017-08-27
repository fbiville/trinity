/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.liquigraph.cypher.neo4jv3;

import io.github.liquigraph.cypher.ClosedTransaction;
import io.github.liquigraph.cypher.Either;
import io.github.liquigraph.cypher.Neo4jVersionDetector;
import io.github.liquigraph.cypher.ResultData;
import io.github.liquigraph.cypher.ResultError;
import io.github.liquigraph.cypher.Row;
import io.github.liquigraph.cypher.SemanticVersion;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.liquigraph.cypher.Assertions.assertThat;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

public class EmbeddedClientTest {

    private static final SemanticVersion CURRENT_NEO4J_VERSION = Neo4jVersionDetector.detect(EmbeddedClientTest.class.getResourceAsStream("/filtered/version.txt"));
    @Rule public final EmbeddedGraphDatabaseRule graphDatabaseRule = new EmbeddedGraphDatabaseRule(CURRENT_NEO4J_VERSION);

    private EmbeddedClient subject;

    @Before
    public void prepare() {
        subject = new EmbeddedClient(graphDatabaseRule.getGraphDatabaseService());
    }

    @Test
    public void executes_statements_in_single_transaction() {
        Either<List<ResultError>, List<ResultData>> result =
            subject.runSingleTransaction("MATCH (n) RETURN COUNT(n)", "CREATE (n:Bolt {name: 'Usain'}) RETURN n.name");

        assertThat(result).isRight();
        List<ResultData> data = result.getRight();
        assertThat(data)
            .containsExactly(
                new ResultData(
                    singletonList("COUNT(n)"),
                    singletonList(
                        singleColumnRow("COUNT(n)", 0L))),
                new ResultData(
                    singletonList("n.name"),
                    singletonList(
                        singleColumnRow("n.name", "Usain"))));
        List<Long> counts = graphDatabaseRule.doInTransaction((tx, db) -> {
            Result executionResult = db.execute("MATCH (n) RETURN count(n) AS count");
            return executionResult
                .columnAs("count")
                .map(Object::toString)
                .map(Long::valueOf)
                .stream().collect(toList());
        });
        assertThat(counts).containsExactly(1L);

    }

    @Test
    public void returns_errors_from_invalid_statements() {
        Either<List<ResultError>, List<ResultData>> result = subject.runSingleTransaction(
            "MATCH (n) RETURN COUNT(n)",
            "JEU, SET et MATCH -- oops not a valid query");

        assertThat(result).isLeft();
        List<ResultError> data = result.getLeft();
        assertThat(data)
            .containsExactly(
                new ResultError(
                    "Neo.ClientError.Statement.SyntaxError",
                    "Invalid input 'J': expected <init> (line 1, column 1 (offset: 0))\n" +
                        "\"JEU, SET et MATCH -- oops not a valid query\"\n" +
                        " ^"));
    }

    @Test
    public void opens_transaction() {
        Either<List<ResultError>, OngoingLocalTransaction> result = subject.openTransaction(
            "RETURN [1,2,3] AS x"
        );

        assertThat(result).isRight();
        OngoingLocalTransaction localTransaction = result.getRight();
        assertThat(localTransaction.getTransaction()).isNotNull();
        assertThat(localTransaction.getResultData())
            .containsExactly(new ResultData(
                singletonList("x"),
                singletonList(singleColumnRow("x", asList(1L, 2L, 3L)))
            ));
    }

    @Test
    public void executes_in_open_transaction() {
        Either<List<ResultError>, OngoingLocalTransaction> openTransaction = subject.openTransaction();
        Either<List<ResultError>, OngoingLocalTransaction> result = subject.execute(openTransaction.getRight(), "RETURN [4,5,6] AS x");

        assertThat(result).isRight();
        OngoingLocalTransaction localTransaction = result.getRight();
        assertThat(localTransaction.getResultData())
            .containsExactly(new ResultData(
                singletonList("x"),
                singletonList(singleColumnRow("x", asList(4L, 5L, 6L))
            )));
    }

    @Test
    public void commits_an_open_transaction() {
        Either<List<ResultError>, OngoingLocalTransaction> openTransaction = subject.openTransaction();
        Either<List<ResultError>, ClosedTransaction> result = subject.commit(
            openTransaction.getRight(),
            "CREATE (n:Foo {type:'Fighter'}) RETURN n.type");

        assertThat(result).isRight();
        ClosedTransaction completedTransaction = result.getRight();
        assertThat(completedTransaction.isRolledBack()).overridingErrorMessage("Transaction must not be rolled back").isFalse();
        assertThat(completedTransaction.getResultData())
            .containsExactly(new ResultData(
                singletonList("n.type"),
                singletonList(singleColumnRow("n.type", "Fighter")
                )));

        GraphDatabaseService graphDatabase = graphDatabaseRule.getGraphDatabaseService();
        try (Transaction ignored = graphDatabase.beginTx()) {
            assertThat(graphDatabase.getAllNodes())
                .overridingErrorMessage("The node insertion must be committed")
                .hasSize(1);
        }
    }

    @Test
    public void rolls_back_an_open_transaction() {
        Either<List<ResultError>, OngoingLocalTransaction> openTransaction = subject.openTransaction("CREATE (n:Bar {type:'Ry White'}) RETURN n.type");
        Either<List<ResultError>, ClosedTransaction> result = subject.rollback(openTransaction.getRight());

        assertThat(result).isRight();
        ClosedTransaction completedTransaction = result.getRight();
        assertThat(completedTransaction.isRolledBack()).overridingErrorMessage("Transaction must be rolled back").isTrue();
        assertThat(completedTransaction.getResultData()).isEmpty();

        GraphDatabaseService graphDatabase = graphDatabaseRule.getGraphDatabaseService();
        try (Transaction ignored = graphDatabase.beginTx()) {
            assertThat(graphDatabase.getAllNodes())
                .overridingErrorMessage("The node insertion must be rolled back")
                .isEmpty();
        }
    }

    private Row singleColumnRow(String column, Object value) {
        Map<String, Object> map = new HashMap<>((int) Math.ceil(1 / 0.75));
        map.put(column, value);
        return new Row(map);
    }

}