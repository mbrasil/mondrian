/*
// $Id$
// This software is subject to the terms of the Common Public License
// Agreement, available at the following URL:
// http://www.opensource.org/licenses/cpl.html.
// Copyright (C) 2004-2007 Julian Hyde and others
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.rolap;

import mondrian.test.FoodMartTestCase;
import mondrian.test.TestContext;
import mondrian.test.SqlPattern;
import mondrian.rolap.agg.GroupingSet;
import mondrian.rolap.agg.CellRequest;
import mondrian.rolap.agg.ValueColumnPredicate;
import mondrian.rolap.sql.SqlQuery;
import mondrian.rolap.cache.CachePool;
import mondrian.olap.*;

import java.util.List;
import java.util.ArrayList;

/**
 * To support all <code>Batch</code> related tests.
 *
 * @author Thiyagu
 * @version $Id$
 * @since 06-Jun-2007
 */
public class BatchTestCase extends FoodMartTestCase {

    public BatchTestCase(String name) {
        super(name);
    }

    public BatchTestCase() {
    }

    protected final String tableTime = "time_by_day";
    protected final String tableProductClass = "product_class";
    protected final String tableCustomer = "customer";
    protected final String fieldYear = "the_year";
    protected final String fieldProductFamily = "product_family";
    protected final String fieldProductDepartment = "product_department";
    protected final String[] fieldValuesYear = {"1997"};
    protected final String[] fieldValuesProductFamily =
        {"Food", "Non-Consumable", "Drink"};
    protected final String[] fieldValueProductDepartment =
        {"Periodicals", "Breakfast Foods", "Eggs", "Household",
            "Alcoholic Beverages",
            "Beverages", "Frozen Foods", "Dairy",
            "Health and Hygiene", "Seafood", "Baked Goods",
            "Checkout", "Canned Products", "Baking Goods", "Meat",
            "Carousel", "Starchy Foods", "Deli", "Produce",
            "Canned Foods", "Snacks", "Snack Foods"};
    protected final String[] fieldValuesGender = {"M", "F"};
    protected final String cubeNameSales = "Sales";
    protected final String measureUnitSales = "[Measures].[Unit Sales]";
    protected String fieldGender = "gender";

    protected FastBatchingCellReader.Batch createBatch(
        FastBatchingCellReader fbcr,
        String[] tableNames, String[] fieldNames, String[][] fieldValues,
        String cubeName, String measure)
    {
        List<String> values = new ArrayList<String>();
        for (int i = 0; i < tableNames.length; i++) {
            values.add(fieldValues[i][0]);
        }
        FastBatchingCellReader.Batch batch = fbcr.new Batch(
            createRequest(cubeName, measure,
                tableNames, fieldNames, values.toArray(new String[0])));

        addRequests(batch, cubeName, measure, tableNames, fieldNames,
            fieldValues, new ArrayList<String>(), 0);
        return batch;

    }

    private void addRequests(
        FastBatchingCellReader.Batch batch,
        String cubeName,
        String measure,
        String[] tableNames,
        String[] fieldNames,
        String[][] fieldValues,
        List<String> selectedValues,
        int currPos)
    {
        if (currPos < fieldNames.length) {
            for (int j = 0; j < fieldValues[currPos].length; j++) {
                selectedValues.add(fieldValues[currPos][j]);
                addRequests(batch, cubeName, measure, tableNames,
                    fieldNames, fieldValues, selectedValues, currPos + 1);
                selectedValues.remove(fieldValues[currPos][j]);
            }
        } else {
            batch.add(createRequest(cubeName, measure, tableNames,
                fieldNames, selectedValues.toArray(new String[0])));
        }
    }

    protected GroupingSet getGroupingSet(
        String[] tableNames, String[] fieldNames, String[][] fieldValues,
        String cubeName, String measure)
    {
        FastBatchingCellReader.Batch batch =
            createBatch(new FastBatchingCellReader(getCube(cubeName)),
                tableNames, fieldNames,
                fieldValues, cubeName,
                measure);
        GroupingSetsCollector collector = new GroupingSetsCollector(true);
        batch.loadAggregation(collector);
        return collector.getGroupingSets().get(0);
    }

    void assertRequestSql(
        CellRequest[] requests, SqlPattern[] patterns, String cubeName)
    {
        RolapStar star = requests[0].getMeasure().getStar();
        SqlQuery.Dialect sqlDialect = star.getSqlQueryDialect();
        SqlPattern.Dialect d = SqlPattern.Dialect.get(sqlDialect);
        SqlPattern sqlPattern = SqlPattern.getPattern(d, patterns);
        if (sqlPattern == null) {
            // If the dialect is not one in the pattern set, do not run the
            // test. We do not print any warning message.
            return;
        }

        String sql = sqlPattern.getSql();
        String trigger = sqlPattern.getTriggerSql();

        // Create a dummy DataSource which will throw a 'bomb' if it is asked
        // to execute a particular SQL statement, but will otherwise behave
        // exactly the same as the current DataSource.
        RolapUtil.threadHooks.set(new TriggerHook(trigger));
        Bomb bomb;
        try {
            FastBatchingCellReader fbcr =
                new FastBatchingCellReader(getCube(cubeName));
            for (CellRequest request : requests) {
                fbcr.recordCellRequest(request);
            }
            fbcr.loadAggregations();
            bomb = null;
        } catch (Bomb e) {
            bomb = e;
        } finally {
            RolapUtil.threadHooks.set(null);
        }
        assertTrue(bomb != null);
        assertEquals(replaceQuotes(sql), replaceQuotes(bomb.sql));
    }

    /**
     * Checks that a given MDX query results in a particular SQL statement
     * being generated.
     *
     * @param mdxQuery MDX query
     * @param patterns Set of patterns for expected SQL statements
     */
    protected void assertQuerySql(String mdxQuery, SqlPattern[] patterns) {
        assertQuerySqlOrNot(getTestContext(), mdxQuery, patterns, false, true);
    }

    /**
     * Checks that a given MDX query results in a particular SQL statement
     * being generated.
     *
     * @param testContext non-default test context if required
     * @param mdxQuery MDX query
     * @param patterns Set of patterns for expected SQL statements
     */
    protected void assertQuerySql(
        TestContext testContext, String mdxQuery, SqlPattern[] patterns) {
        assertQuerySqlOrNot(testContext, mdxQuery, patterns, false, true);
    }

    /**
     * Checks that a given MDX query does not result in a particular SQL
     * statement being generated.
     *
     * @param mdxQuery MDX query
     * @param patterns Set of patterns for expected SQL statements
     */
    protected void assertNoQuerySql(String mdxQuery, SqlPattern[] patterns) {
        assertQuerySqlOrNot(getTestContext(), mdxQuery, patterns, true, true);
    }

    /**
     * Checks that a given MDX query results (or does not result) in a
     * particular SQL statement being generated.
     *
     * <p>Runs the MDX query once for each SQL pattern in the current
     * dialect. If there are multiple patterns, runs the MDX query multiple
     * times, and expects to see each SQL statement appear. If there are no
     * patterns in this dialect, the test trivially succeeds.
     *
     * @param testContext non-default test context if required
     * @param mdxQuery MDX query
     * @param patterns Set of patterns
     * @param negative false to assert if SQL is generated;
     *                 true to assert if SQL is NOT generated
     * @param clearCache whether to clear cache before executing the MDX query
     */
    protected void assertQuerySqlOrNot(
        TestContext testContext,
        String mdxQuery,
        SqlPattern[] patterns,
        boolean negative,
        boolean clearCache)
    {
        final Connection connection = testContext.getConnection();
        final Query query = connection.parseQuery(mdxQuery);
        final Cube cube = query.getCube();
        RolapSchema schema = (RolapSchema) cube.getSchema();

        // Run the test once for each pattern in this dialect.
        // (We could optimize and run it once, collecting multiple queries, and
        // comparing all queries at the end.)
        SqlQuery.Dialect dialect = schema.getDialect();
        SqlPattern.Dialect d = SqlPattern.Dialect.get(dialect);
        for (SqlPattern sqlPattern : patterns) {
            if (!sqlPattern.hasDialect(d)) {
                // If the dialect is not one in the pattern set, do not run the
                // test. We do not print any warning message.
                continue;
            }

            String sql = sqlPattern.getSql();
            String trigger = sqlPattern.getTriggerSql();

            if (clearCache) {
                // Clear the cache for the Sales cube, so the query runs as if
                // for the first time. (TODO: Cleaner way to do this.)
                RolapHierarchy hierarchy =
                    (RolapHierarchy) getConnection().getSchema().
                    lookupCube("Sales", true).lookupHierarchy("Store", false);
                SmartMemberReader memberReader =
                    (SmartMemberReader) hierarchy.getMemberReader();
                memberReader.mapLevelToMembers.cache.clear();
                memberReader.mapMemberToChildren.cache.clear();
            }

            // Create a dummy DataSource which will throw a 'bomb' if it is
            // asked to execute a particular SQL statement, but will otherwise
            // behave exactly the same as the current DataSource.
            RolapUtil.threadHooks.set(new TriggerHook(trigger));

            Bomb bomb;
            try {
                if (clearCache) {
                    // Flush the cache, to ensure that the query gets executed.
                    ((RolapCube)cube).clearCachedAggregations(true);
                    CachePool.instance().flush();
                }

                final Result result = connection.execute(query);
                Util.discard(result);
                bomb = null;
            } catch (Bomb e) {
                bomb = e;
            } finally {
                RolapUtil.threadHooks.set(null);
            }
            if (negative) {
                if (bomb != null) {
                    fail("forbidden query [" + sql + "] detected");
                }
            } else {
                if (bomb == null) {
                    fail("expected query [" + sql + "] did not occur");
                }
                assertEquals(replaceQuotes(sql), replaceQuotes(bomb.sql));
            }
        }
    }

    private static String replaceQuotes(String s) {
        s = s.replace('`', '\"');
        s = s.replace('\'', '\"');
        return s;
    }

    CellRequest createRequest(
        final String cube, final String measure,
        final String table, final String column, final String value)
    {
        RolapStar.Measure starMeasure = getMeasure(cube, measure);
        CellRequest request = new CellRequest(starMeasure, false, false);
        if (table != null) {
            final RolapStar star = starMeasure.getStar();
            final RolapStar.Column storeTypeColumn = star.lookupColumn(
                table, column);
            request.addConstrainedColumn(
                storeTypeColumn,
                new ValueColumnPredicate(storeTypeColumn, value));
        }
        return request;
    }

    CellRequest createRequest(
        final String cube, final String measureName,
        final String[] tables, final String[] columns, final String[] values)
    {
        RolapStar.Measure starMeasure = getMeasure(cube, measureName);
        CellRequest request = new CellRequest(starMeasure, false, false);
        final RolapStar star = starMeasure.getStar();
        for (int i = 0; i < tables.length; i++) {
            String table = tables[i];
            String column = columns[i];
            String value = values[i];
            final RolapStar.Column storeTypeColumn =
                star.lookupColumn(table, column);
            request.addConstrainedColumn(
                storeTypeColumn,
                new ValueColumnPredicate(storeTypeColumn, value));
        }
        return request;
    }

    protected RolapStar.Measure getMeasure(String cube, String measureName) {
        final Connection connection = getFoodMartConnection();
        final boolean fail = true;
        Cube salesCube = connection.getSchema().lookupCube(cube, fail);
        Member measure = salesCube.getSchemaReader(null).getMemberByUniqueName(
            Util.explode(measureName), fail);
        RolapStar.Measure starMeasure = RolapStar.getStarMeasure(measure);
        return starMeasure;
    }

    protected Connection getFoodMartConnection() {
        return TestContext.instance().getFoodMartConnection();
    }

    protected RolapCube getCube(final String cube) {
        final Connection connection = getFoodMartConnection();
        final boolean fail = true;
        return (RolapCube) connection.getSchema().lookupCube(cube, fail);
    }

    /**
     * Fake exception to interrupt the test when we see the desired query.
     * It is an {@link Error} because we need it to be unchecked
     * ({@link Exception} is checked), and we don't want handlers to handle
     * it.
     */
    static class Bomb extends Error {
        final String sql;

        Bomb(final String sql) {
            this.sql = sql;
        }
    }

    private static class TriggerHook implements RolapUtil.ExecuteQueryHook {
        private final String trigger;

        public TriggerHook(String trigger) {
            this.trigger = trigger;
        }

        private boolean matchTrigger(String sql) {
            if (trigger == null) {
                return true;
            }
            // different versions of mysql drivers use different quoting, so
            // ignore quotes
            String s = replaceQuotes(sql);
            String t = replaceQuotes(trigger);
            return s.startsWith(t);
        }

        public void onExecuteQuery(String sql) {
            if (matchTrigger(sql)) {
                throw new Bomb(sql);
            }
        }
    }
}

// End BatchTestCase.java