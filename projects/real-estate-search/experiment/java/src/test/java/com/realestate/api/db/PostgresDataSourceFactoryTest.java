package com.realestate.api.db;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresDataSourceFactoryTest {

    @Test
    void parseConnectionUrlSupportsPostgresUrisAndJdbcUrls() {
        PostgresDataSourceFactory.JdbcConnectionConfig full = PostgresDataSourceFactory.parseConnectionUrl(
            "postgres://postgres:secret%21@db:5432/realestate?sslmode=disable"
        );
        assertThat(full.jdbcUrl()).isEqualTo("jdbc:postgresql://db:5432/realestate?sslmode=disable");
        assertThat(full.username()).isEqualTo("postgres");
        assertThat(full.password()).isEqualTo("secret!");

        PostgresDataSourceFactory.JdbcConnectionConfig userOnly = PostgresDataSourceFactory.parseConnectionUrl(
            "postgresql://admin@localhost/app"
        );
        assertThat(userOnly.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost/app");
        assertThat(userOnly.username()).isEqualTo("admin");
        assertThat(userOnly.password()).isNull();

        PostgresDataSourceFactory.JdbcConnectionConfig noAuth = PostgresDataSourceFactory.parseConnectionUrl(
            "postgres://localhost/mydb"
        );
        assertThat(noAuth.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost/mydb");
        assertThat(noAuth.username()).isNull();
        assertThat(noAuth.password()).isNull();

        PostgresDataSourceFactory.JdbcConnectionConfig jdbc = PostgresDataSourceFactory.parseConnectionUrl(
            "jdbc:postgresql://localhost:5432/mydb"
        );
        assertThat(jdbc.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/mydb");
        assertThat(jdbc.username()).isNull();
        assertThat(jdbc.password()).isNull();
    }

    @Test
    void parseConnectionUrlRejectsInvalidUrls() {
        assertThatThrownBy(() -> PostgresDataSourceFactory.parseConnectionUrl(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostgresDataSourceFactory.parseConnectionUrl("   "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostgresDataSourceFactory.parseConnectionUrl("mysql://localhost:3306/db"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostgresDataSourceFactory.parseConnectionUrl("postgres:///db"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostgresDataSourceFactory.parseConnectionUrl("postgres://localhost"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostgresDataSourceFactory.parseConnectionUrl("postgres://localhost/"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createHikariConfigPopulatesDriverPoolAndCredentials() {
        HikariConfig config = PostgresDataSourceFactory.createHikariConfig(
            "postgres://pguser:pgpass@dbhost:5433/realestate"
        );

        assertThat(config.getJdbcUrl()).isEqualTo("jdbc:postgresql://dbhost:5433/realestate");
        assertThat(config.getUsername()).isEqualTo("pguser");
        assertThat(config.getPassword()).isEqualTo("pgpass");
        assertThat(config.getDriverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(config.getMaximumPoolSize()).isEqualTo(10);
        assertThat(config.getMinimumIdle()).isEqualTo(1);
        assertThat(config.getPoolName()).isEqualTo("real-estate-hikari");
    }

    @Test
    void initializeRepositoryClosesPoolWhenEnsureSchemaFailsAndReturnsRepoOnSuccess() {
        var closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        var failingRepo = new PostgresListingRepository(
            (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                javax.sql.DataSource.class.getClassLoader(),
                new Class<?>[] { javax.sql.DataSource.class },
                (p, m, a) -> {
                    if ("getConnection".equals(m.getName())) {
                        throw new java.sql.SQLException("connect failed");
                    }
                    return null;
                }
            )
        );

        assertThatThrownBy(() -> PostgresDataSourceFactory.initializeRepository(failingRepo, () -> closed.set(true)))
            .isInstanceOf(PersistenceException.class);
        assertThat(closed).isTrue();

        var stmt = (java.sql.Statement) java.lang.reflect.Proxy.newProxyInstance(
            java.sql.Statement.class.getClassLoader(),
            new Class<?>[] { java.sql.Statement.class },
            (p, m, a) -> "execute".equals(m.getName()) ? true : null
        );
        var conn = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
            java.sql.Connection.class.getClassLoader(),
            new Class<?>[] { java.sql.Connection.class },
            (p, m, a) -> "createStatement".equals(m.getName()) ? stmt : null
        );
        var okRepo = new PostgresListingRepository(
            (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                javax.sql.DataSource.class.getClassLoader(),
                new Class<?>[] { javax.sql.DataSource.class },
                (p, m, a) -> "getConnection".equals(m.getName()) ? conn : null
            )
        );
        var closedOk = new java.util.concurrent.atomic.AtomicBoolean(false);
        assertThat(PostgresDataSourceFactory.initializeRepository(okRepo, () -> closedOk.set(true))).isSameAs(okRepo);
        assertThat(closedOk).isFalse();
    }
}
