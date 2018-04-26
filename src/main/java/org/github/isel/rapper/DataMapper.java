package org.github.isel.rapper;

import javafx.util.Pair;
import org.github.isel.rapper.exceptions.DataMapperException;
import org.github.isel.rapper.utils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class DataMapper<T extends DomainObject<K>, K> implements Mapper<T, K> {

    private final Logger log = LoggerFactory.getLogger(DataMapper.class);
    private final Class<T> type;
    private final MapperSettings mapperSettings;
    private final Constructor<T> constructor;
    private final ExternalsHandler<T, K> externalHandler;

    private Class<?> primaryKey = null;
    private Constructor<?> primaryKeyConstructor = null;

    public DataMapper(Class<T> type){
        this.type = type;
        try {
            Class<?>[] declaredClasses = type.getDeclaredClasses();
            if(declaredClasses.length > 0){
                primaryKey = declaredClasses[0];
                primaryKeyConstructor = primaryKey.getConstructor();
            }

            constructor = type.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        mapperSettings = new MapperSettings(type);
        externalHandler = new ExternalsHandler<>(mapperSettings.getIds(), mapperSettings.getExternals(), primaryKey, primaryKeyConstructor);
    }

    @Override
    public <R> CompletableFuture<List<T>> findWhere(Pair<String, R>... values){
        String query = Arrays.stream(values)
                .map(p -> p.getKey() + " = ? ")
                .collect(Collectors.joining(" AND ", mapperSettings.getSelectQuery() + " WHERE ", ""));
        SqlConsumer<PreparedStatement> consumer = s -> {
            for (int i = 0; i < values.length; i++) {
                s.setObject(i+1, values[i].getValue());
            }
        };
        SqlFunction<PreparedStatement, Stream<T>> streamSqlFunction = s -> stream(s, s.getResultSet());
        return SQLUtils.execute(query, consumer.wrap())
                .thenApply(streamSqlFunction.wrap())
                .thenApply(s -> s.collect(Collectors.toList()))
                .exceptionally(throwable -> {
                    log.info("Couldn't execute query on {}.", type.getSimpleName());
                    throwable.printStackTrace();
                    return Collections.emptyList();
                });
    }

    @Override
    public CompletableFuture<Optional<T>> findById(K id) {
        UnitOfWork unitOfWork = UnitOfWork.getCurrent();
        SqlFunction<PreparedStatement, Stream<T>> func = stmt -> stream(stmt, stmt.getResultSet());
        return SQLUtils.execute(mapperSettings.getSelectByIdQuery(), stmt -> SQLUtils.setValuesInStatement(mapperSettings.getIds().stream(), stmt, id))
                .thenApply(ps -> {
                    UnitOfWork.setCurrent(unitOfWork);
                    Optional<T> optionalT = func.wrap().apply(ps).findFirst();
                    optionalT.ifPresent(t -> externalHandler.populateExternals(t).join());
                    return optionalT;
                })
                .exceptionally(throwable -> {
                    log.info("Couldn't execute query on {}.", type.getSimpleName());
                    throwable.printStackTrace();
                    return Optional.empty();
                });
    }

    @Override
    public CompletableFuture<List<T>> findAll() {
        UnitOfWork current = UnitOfWork.getCurrent();
        SqlFunction<PreparedStatement, Stream<T>> func = stmt -> stream(stmt, stmt.getResultSet());
        return SQLUtils.execute(mapperSettings.getSelectQuery(), s ->{})
                .thenApply(func.wrap())
                .thenApply(tStream -> {
                    UnitOfWork.setCurrent(current);
                    return tStream.peek(t -> externalHandler.populateExternals(t).join());
                })
                .thenApply(tStream1 -> tStream1.collect(Collectors.toList()))
                .exceptionally(throwable -> {
                    log.info("Couldn't execute query on {}.", type.getSimpleName());
                    throwable.printStackTrace();
                    return Collections.emptyList();
                });
    }

    @Override
    public CompletableFuture<Boolean> create(T obj) {
        SqlConsumer<PreparedStatement> consumer = stmt -> {
            ResultSet rs = stmt.getResultSet();
            setVersion(obj, rs);
            setGeneratedKeys(obj, rs);
        };

        //This is only done once because it will be recursive (The parentClass will check if its parentClass is a DomainObject and therefore call its insert)
        boolean[] parentSuccess = { true };
        getParentMapper().ifPresent(objectDataMapper -> parentSuccess[0] = objectDataMapper.create(obj).join());
        //If the id is autogenerated, it will be set on the obj by the insert of the parent

        if(!parentSuccess[0]) return CompletableFuture.completedFuture(parentSuccess[0]);

        return SQLUtils.execute(mapperSettings.getInsertQuery(), stmt ->
            SQLUtils.setValuesInStatement(
                    Stream.concat(mapperSettings.getIds().stream().filter(sqlFieldId -> !sqlFieldId.identity || sqlFieldId.isFromParent), mapperSettings.getColumns().stream())
                            .sorted(Comparator.comparing(SqlField::byInsert)), stmt, obj)
        )
                .thenApply(ps ->{
                    consumer.wrap().accept(ps);
                    return true;
                })
                .exceptionally(throwable -> {
                    log.info("Couldn't create {}. \nReason: {}", type.getSimpleName(), throwable.getMessage());
                    return false;
                });
    }

    @Override
    public CompletableFuture<Boolean> createAll(Iterable<T> t) {
        //TODO
        return null;
    }

    @Override
    public CompletableFuture<Boolean> update(T obj) {
        SqlConsumer<PreparedStatement> func = s -> setVersion(obj, s.getResultSet());

        //Updates parents first
        boolean[] parentSuccess = { true };
        getParentMapper().ifPresent(objectDataMapper -> parentSuccess[0] = objectDataMapper.update(obj).join());

        if(!parentSuccess[0]) return CompletableFuture.completedFuture(parentSuccess[0]);

        return SQLUtils.execute(mapperSettings.getUpdateQuery(), stmt -> {
            SQLUtils.setValuesInStatement(
                    Stream.concat(mapperSettings.getIds().stream(), mapperSettings.getColumns().stream())
                            .sorted(Comparator.comparing(SqlField::byUpdate)), stmt, obj);
            //Since each object has its own version, we want the version from type not from the subClass
            SqlFunction<T, Long> getVersion = t -> {
                Field f = type.getDeclaredField("version");
                f.setAccessible(true);
                return (Long) f.get(t);
            };

            SqlConsumer<PreparedStatement> setVersion = preparedStatement ->
                    preparedStatement.setLong(
                            mapperSettings.getColumns().size() + mapperSettings.getIds().size() + 1,
                            getVersion.wrap().apply(obj)
                    );
            setVersion.wrap().accept(stmt);
        })
                .thenApply(ps -> {
                    func.wrap().accept(ps);
                    return true;
                })
                .exceptionally(throwable -> {
                    log.info("Couldn't update {}. \nReason: {}", type.getSimpleName(), throwable.getMessage());
                    return false;
                });
    }

    @Override
    public CompletableFuture<Boolean> updateAll(Iterable<T> t) {
        //TODO
        return null;
    }

    @Override
    public CompletableFuture<Boolean> deleteById(K k) {
        //TODO
        return null;
    }

    @Override
    public CompletableFuture<Boolean> delete(T obj) {
        UnitOfWork unit = UnitOfWork.getCurrent();
        return SQLUtils.execute(mapperSettings.getDeleteQuery(), stmt ->
                SQLUtils.setValuesInStatement(mapperSettings.getIds().stream(), stmt, obj)
        )
                .thenCompose(preparedStatement -> {
                    UnitOfWork.setCurrent(unit);
                    return getParentMapper()
                            .map(objectDataMapper -> objectDataMapper.delete(obj))
                            .orElse(CompletableFuture.completedFuture(true));
                })
                .exceptionally(throwable -> {
                    log.info("Couldn't delete {}. \nReason: {}", type.getSimpleName(), throwable.getMessage());
                    return false;
                });
    }

    @Override
    public CompletableFuture<Boolean> deleteAll(Iterable<K> keys) {
        //TODO
        return null;
    }

    private Stream<T> stream(Statement statement, ResultSet rs) throws DataMapperException{
        return StreamSupport.stream(new Spliterators.AbstractSpliterator<T>(
                Long.MAX_VALUE, Spliterator.ORDERED) {
            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                try {
                    if(!rs.next())return false;
                    action.accept(mapper(rs));
                    return true;
                } catch (SQLException e) {
                    throw new DataMapperException(e);
                }
            }
        }, false).onClose(() -> {
            try {
                rs.close();
                statement.close();
            } catch (SQLException e) {
                throw new DataMapperException(e.getMessage(), e);
            }
        });
    }

    private T mapper(ResultSet rs){
        try {
            T t = constructor.newInstance();
            Object primaryKey = primaryKeyConstructor != null ? primaryKeyConstructor.newInstance() : null;

            //Set t's primary key field to primaryKey if its a composed primary key
            if(primaryKey != null) {
                SqlConsumer<Field> fieldConsumer = field -> {
                    field.setAccessible(true);
                    field.set(t, primaryKey);
                };

                Arrays.stream(type.getDeclaredFields())
                        .filter(field -> field.getType() == this.primaryKey)
                        .findFirst()
                        .ifPresent(fieldConsumer.wrap());
            }

            SqlConsumer<SqlField> fieldSetter = f -> {
                f.field.setAccessible(true);
                try{
                    f.field.set(t, rs.getObject(f.name));
                }
                catch (IllegalArgumentException e){ //If IllegalArgumentException is caught, is because field is from primaryKeyClass
                    if(primaryKey != null)
                        f.field.set(primaryKey, rs.getObject(f.name));
                    else throw new DataMapperException(e);
                }
            };
            mapperSettings
                    .getAllFields()
                    .stream()
                    .filter(field -> mapperSettings.getFieldPredicate().test(field.field))
                    .forEach(fieldSetter.wrap());

            return t;
        } catch (IllegalAccessException | InstantiationException | InvocationTargetException e) {
            throw new DataMapperException(e);
        }
    }

    /**
     * Gets the mapper of the parent of T.
     * If the parent implements DomainObject (meaning it has a mapper), gets its mapper, else returns an empty Optional.
     * @return Optional of DataMapper or empty Optional
     */
    private Optional<Mapper<? super T, ? super K>> getParentMapper(){
        Class<? super T> aClass = type.getSuperclass();
        if(aClass != Object.class && DomainObject.class.isAssignableFrom(aClass)) {
            Mapper<? super T, ? super K> classMapper = MapperRegistry.getRepository((Class<DomainObject>) aClass).getMapper();
            return Optional.of(classMapper);
        }
        return Optional.empty();
    }

    /**
     * rs.next() should be called before calling this method
     * @param obj
     * @param rs
     */
    private void setGeneratedKeys(T obj, ResultSet rs) {
        SqlConsumer<SqlField> fieldSqlConsumer = field -> {
            field.field.setAccessible(true);
            field.field.set(obj, rs.getObject(field.name));
        };

        mapperSettings
                .getIds()
                .stream()
                .filter(f -> f.identity && !f.isFromParent)
                .forEach(fieldSqlConsumer.wrap());
    }

    private void setVersion(T obj, ResultSet rs) throws SQLException, IllegalAccessException {
        if(rs.next()) {
            Field version;
            try {
                version = type.getDeclaredField("version");
                version.setAccessible(true);
                version.set(obj, rs.getLong("version"));
            } catch (NoSuchFieldException ignored) {
                log.info("version field not found on " + type.getSimpleName());
            }
        }
        else throw new DataMapperException("Couldn't get version.");
    }

    public String getSelectQuery() {
        return mapperSettings.getSelectQuery();
    }

    public String getInsertQuery() {
        return mapperSettings.getInsertQuery();
    }

    public String getUpdateQuery() {
        return mapperSettings.getUpdateQuery();
    }

    public String getDeleteQuery() {
        return mapperSettings.getDeleteQuery();
    }
}
