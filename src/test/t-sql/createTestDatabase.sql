IF DB_ID ('PS_TEST_API_DATABASE') IS NULL
	CREATE DATABASE PS_TEST_API_DATABASE;
GO

use PS_TEST_API_DATABASE

if OBJECT_ID('Person') is not null
  drop table Person
go
create table Person (
	nif int primary key,
	[name] nvarchar(50),
	birthday date,
  version rowversion
)
go

if OBJECT_ID('Car') is not null
  drop table Car
go
create table Car (
  owner int,
  plate varchar(6),
  brand varchar(20),
  model varchar(20),
  version rowversion,

  PRIMARY KEY (owner, plate)
)
go

if OBJECT_ID('Student') is not null
  drop table Student
go
create table Student (
  nif int references Person,
  studentNumber int,
  version rowversion,

  PRIMARY KEY (nif)
)
go

if OBJECT_ID('TopStudent') is not null
  drop table TopStudent
go
create table TopStudent (
  nif int references Student,
  topGrade int,
  year int,
  version rowversion,

  PRIMARY KEY (nif)
)
go

if OBJECT_ID('Company') is not null
  drop table Company
go
create table Company (
  id int,
  cid int,
  motto varchar(20),
  version rowversion,

  PRIMARY KEY (id, cid)
)
go

if OBJECT_ID('Employee') is not null
  drop table Employee
go
create table Employee (
  id int identity,
  name varchar(20),
  companyId int,
  companyCid int,
  version rowversion,

  PRIMARY KEY (id),
  FOREIGN KEY (companyId, companyCid) references Company (id, cid)
)
go

if OBJECT_ID('EmployeeJunior') is not null
  drop table EmployeeJunior
go
create table EmployeeJunior (
  id int,
  juniorsYears int,
  version rowversion,

  PRIMARY KEY (id),
  FOREIGN KEY (id) references Employee (id)
)
go

if OBJECT_ID('CompanyEmployee') is not null
  drop table CompanyEmployee
go
create table CompanyEmployee (
  employeeId int references Employee,
  companyId int,
  companyCid int,
  version rowversion,

  PRIMARY KEY (employeeId, companyId, companyCid),
  FOREIGN KEY (companyId, companyCid) REFERENCES Company (id, cid)
)
go
