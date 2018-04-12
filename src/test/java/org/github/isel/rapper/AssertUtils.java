package org.github.isel.rapper;

import org.github.isel.rapper.*;
import org.github.isel.rapper.utils.UnitOfWork;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class AssertUtils {

    public static void assertPerson(Person person, ResultSet rs) throws SQLException {
        assertEquals(person.getNif(), rs.getInt("nif"));
        assertEquals(person.getName(), rs.getString("name"));
        assertEquals(person.getBirthday(), rs.getDate("birthday"));
        assertEquals(person.getVersion(), rs.getLong("version"));
        assertNotEquals(0, person.getVersion());
    }

    public static void assertCar(Car car, ResultSet rs) throws SQLException {
        assertEquals(car.getIdentityKey().getOwner(), rs.getInt("owner"));
        assertEquals(car.getIdentityKey().getPlate(), rs.getString("plate"));
        assertEquals(car.getBrand(), rs.getString("brand"));
        assertEquals(car.getModel(), rs.getString("model"));
        assertEquals(car.getVersion(), rs.getLong("version"));
        assertNotEquals(0, car.getVersion());
    }

    public static void assertTopStudent(TopStudent topStudent, ResultSet rs) throws SQLException {
        assertEquals(topStudent.getNif(), rs.getInt("nif"));
        assertEquals(topStudent.getName(), rs.getString("name"));
        assertEquals(topStudent.getBirthday(), rs.getDate("birthday"));
        assertEquals(topStudent.getVersion(), rs.getLong("version"));
        assertNotEquals(0, topStudent.getVersion());
        assertEquals(topStudent.getTopGrade(), rs.getInt("topGrade"));
        assertEquals(topStudent.getYear(), rs.getInt("year"));
    }

    public static void assertCompany(Company company, ResultSet rs, UnitOfWork current) throws SQLException {
        assertEquals(company.getIdentityKey().getId(), rs.getInt("id"));
        assertEquals(company.getIdentityKey().getCid(), rs.getInt("cid"));
        assertEquals(company.getMotto(), rs.getString("motto"));
        assertEquals(company.getVersion(), rs.getLong("version"));
        assertNotEquals(0, company.getVersion());

        List<Employee> employees = company.getCurrentEmployees().get();
        PreparedStatement ps = current.getConnection()
                .prepareStatement("select id, name, companyId, companyCid, CAST(version as bigint) version from Employee where companyId = ? and companyCid = ?");
        ps.setInt(1, company.getIdentityKey().getId());
        ps.setInt(2, company.getIdentityKey().getCid());
        rs = ps.executeQuery();

        while(rs.next()){
            assertEmployee(employees.remove(0), rs);
        }
    }

    public static void assertEmployee(Employee employee, ResultSet rs) throws SQLException {
        assertEquals(employee.getId(), rs.getInt("id"));
        assertEquals(employee.getName(), rs.getString("name"));
        assertEquals(employee.getCompanyId(), rs.getInt("companyId"));
        assertEquals(employee.getCompanyCid(), rs.getInt("companyCid"));
        assertEquals(employee.getVersion(), rs.getLong("version"));
        assertNotEquals(0, employee.getVersion());
    }
}
