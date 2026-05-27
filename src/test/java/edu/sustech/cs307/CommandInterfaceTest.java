package edu.sustech.cs307;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandInterfaceTest {
    @Test
    void splitSqlBatchKeepsIncompleteTailAndIgnoresSemicolonsInStrings() {
        DBEntry.SqlBatch batch = DBEntry.splitSqlBatch(
                "CREATE TABLE users (id int);\n"
                        + "INSERT INTO users (id, name) VALUES (1, 'a;lice');\n"
                        + "SELECT * FROM users");

        assertThat(batch.statements()).containsExactly(
                "CREATE TABLE users (id int)",
                "INSERT INTO users (id, name) VALUES (1, 'a;lice')");
        assertThat(batch.remainder()).isEqualTo("\nSELECT * FROM users");
    }
}
