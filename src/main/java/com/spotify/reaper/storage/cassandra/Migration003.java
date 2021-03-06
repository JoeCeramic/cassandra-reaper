
package com.spotify.reaper.storage.cassandra;

import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.utils.UUIDs;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Migration003 {

    private static final Logger LOG = LoggerFactory.getLogger(Migration003.class);

    /** migrate over the repair_schedule table **/
    public static void migrate(Session session) {
        KeyspaceMetadata metadata = session.getCluster().getMetadata().getKeyspace(session.getLoggedKeyspace());
        if(null != metadata.getTable("repair_unit")) {

            LOG.warn("Migrating repair_unit and repair_schedule tables. This may take some minutes…");

            PreparedStatement insertRprUnit = session.prepare(
                    "INSERT INTO repair_unit_v1 (id, cluster_name, keyspace_name, column_families, incremental_repair) VALUES(?, ?, ?, ?, ?)");

            PreparedStatement insertRprSched = session.prepare(
                    "INSERT INTO repair_schedule_v1 (id, repair_unit_id, state, days_between, next_activation, run_history, segment_count, repair_parallelism, intensity, creation_time, owner, pause_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

            PreparedStatement insertRprSchedIdx = session.prepare(
                    "INSERT INTO repair_schedule_by_cluster_and_keyspace(cluster_name, keyspace_name, repair_schedule_id) VALUES(?, ?, ?)");

            Map<Long,UUID> repairUnitIds = new HashMap<>();
            Map<Long,String> repairUnitClusters = new HashMap<>();
            Map<Long,String> repairUnitKeyspaces = new HashMap<>();

            for(Row row : session.execute(QueryBuilder.select().from("repair_unit"))) {
                UUID uuid = UUIDs.timeBased();
                repairUnitIds.put(row.getLong("id"), uuid);
                repairUnitClusters.put(row.getLong("id"), row.getString("cluster_name"));
                repairUnitKeyspaces.put(row.getLong("id"), row.getString("keyspace_name"));

                session.execute(
                        insertRprUnit.bind(
                                uuid,
                                row.getString("cluster_name"),
                                row.getString("keyspace_name"),
                                row.getSet("column_families", String.class),
                                row.getBool("incremental_repair")));
            }
            session.executeAsync("DROP TABLE repair_unit");

            for(Row row : session.execute(QueryBuilder.select().from("repair_schedule"))) {
                UUID uuid = UUIDs.timeBased();
                long repairUnitId = row.getLong("repair_unit_id");
                
                session.execute(
                        insertRprSched.bind(
                            uuid,
                            repairUnitIds.get(repairUnitId),
                            row.getString("state"),
                            row.getInt("days_between"),
                            row.getTimestamp("next_activation"),
                            Collections.emptySet(),
                            row.getInt("segment_count"),
                            row.getString("repair_parallelism"),
                            row.getDouble("intensity"),
                            row.getTimestamp("creation_time"),
                            row.getString("owner"),
                            row.getTimestamp("pause_time")));


                session.executeAsync(insertRprSchedIdx
                        .bind(repairUnitClusters.get(repairUnitId), repairUnitKeyspaces.get(repairUnitId), uuid));
                
                session.executeAsync(insertRprSchedIdx.bind(repairUnitClusters.get(repairUnitId), " ", uuid));
                session.executeAsync(insertRprSchedIdx.bind(" ", repairUnitKeyspaces.get(repairUnitId), uuid));
            }

            session.executeAsync("DROP TABLE repair_schedule");

            LOG.warn("Migration of repair_unit and repair_schedule tables completed.");
        }
    }

    private Migration003(){}
}
