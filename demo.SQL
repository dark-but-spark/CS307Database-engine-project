CREATE TABLE students (id int, name char, age int, gpa float);

INSERT INTO students (id, name, age, gpa) VALUES (1, 'alice', 18, 3.60);
INSERT INTO students (id, name, age, gpa) VALUES (2, 'bob', 19, 3.20);
INSERT INTO students (id, name, age, gpa) VALUES (3, 'carol', 18, 3.85);
INSERT INTO students (id, name, age, gpa) VALUES (4, 'dave', 20, 2.95);
INSERT INTO students (id, name, age, gpa) VALUES (5, 'eve', 21, 3.45);
INSERT INTO students (id, name, age, gpa) VALUES (6, 'frank', 19, 3.10);
INSERT INTO students (id, name, age, gpa) VALUES (7, 'grace', 22, 3.70);
INSERT INTO students (id, name, age, gpa) VALUES (8, 'heidi', 20, 3.95);
INSERT INTO students (id, name, age, gpa) VALUES (9, 'ivan', 18, 2.80);
INSERT INTO students (id, name, age, gpa) VALUES (10, 'judy', 21, 3.30);
INSERT INTO students (id, name, age, gpa) VALUES (11, 'kate', 19, 3.55);
INSERT INTO students (id, name, age, gpa) VALUES (12, 'leo', 20, 3.25);
INSERT INTO students (id, name, age, gpa) VALUES (13, 'mallory', 22, 2.75);
INSERT INTO students (id, name, age, gpa) VALUES (14, 'nick', 18, 3.90);
INSERT INTO students (id, name, age, gpa) VALUES (15, 'olivia', 21, 3.65);
INSERT INTO students (id, name, age, gpa) VALUES (16, 'peggy', 19, 2.60);
INSERT INTO students (id, name, age, gpa) VALUES (17, 'quentin', 20, 3.40);
INSERT INTO students (id, name, age, gpa) VALUES (18, 'ruth', 22, 3.80);
INSERT INTO students (id, name, age, gpa) VALUES (19, 'sybil', 18, 3.15);
INSERT INTO students (id, name, age, gpa) VALUES (20, 'trent', 21, 2.90);
INSERT INTO students (id, name, age, gpa) VALUES (21, 'uma', 19, 3.75);
INSERT INTO students (id, name, age, gpa) VALUES (22, 'victor', 20, 3.05);
INSERT INTO students (id, name, age, gpa) VALUES (23, 'wendy', 22, 3.50);
INSERT INTO students (id, name, age, gpa) VALUES (24, 'xavier', 18, 2.70);
INSERT INTO students (id, name, age, gpa) VALUES (25, 'yvonne', 21, 3.88);
INSERT INTO students (id, name, age, gpa) VALUES (26, 'zack', 19, 3.00);
INSERT INTO students (id, name, age, gpa) VALUES (27, 'amy', 20, 3.35);
INSERT INTO students (id, name, age, gpa) VALUES (28, 'brian', 22, 3.92);
INSERT INTO students (id, name, age, gpa) VALUES (29, 'claire', 18, 3.48);
INSERT INTO students (id, name, age, gpa) VALUES (30, 'daniel', 21, 3.12);

SHOW TABLES;
DESCRIBE students;

SELECT * FROM students;
SELECT id, name, gpa FROM students WHERE age = 19;
SELECT id, name, gpa FROM students WHERE gpa >= 3.50 AND age <= 21;
SELECT id, name FROM students WHERE age = 18 OR gpa > 3.90;

SELECT COUNT(*) FROM students;
SELECT COUNT(id) FROM students WHERE gpa >= 3.50;
SELECT COUNT(DISTINCT age) FROM students;
SELECT MAX(gpa) FROM students;
SELECT MIN(gpa) FROM students;
SELECT age, COUNT(*) FROM students GROUP BY age;
SELECT id, name, gpa FROM students ORDER BY gpa DESC;

CREATE TABLE scores (student_id int, course char, score int);
INSERT INTO scores (student_id, course, score) VALUES (1, 'database', 95);
INSERT INTO scores (student_id, course, score) VALUES (2, 'database', 86);
INSERT INTO scores (student_id, course, score) VALUES (3, 'database', 91);
INSERT INTO scores (student_id, course, score) VALUES (8, 'database', 98);
INSERT INTO scores (student_id, course, score) VALUES (30, 'database', 78);

SELECT students.id, students.name, scores.score
FROM students JOIN scores ON students.id = scores.student_id
WHERE scores.score >= 90;

SELECT id, name FROM students
WHERE id IN (SELECT student_id FROM scores WHERE score >= 90);

SELECT id, name FROM students
WHERE EXISTS (SELECT student_id FROM scores WHERE scores.student_id = students.id AND score < 80);

CREATE INDEX idx_students_id ON students (id);
SELECT id, name, gpa FROM students WHERE id >= 10 AND id < 16;
SELECT id, name FROM students WHERE 20 > id AND id > 15;
SELECT id, name FROM students WHERE id BETWEEN 24 AND 28;

UPDATE students SET gpa = 4.00 WHERE id = 8;
SELECT id, name, gpa FROM students WHERE id = 8;

DELETE FROM students WHERE id = 16;
SELECT id, name FROM students WHERE id >= 15 AND id <= 17;

BEGIN;
UPDATE students SET name = 'rollback' WHERE id = 1;
SAVEPOINT before_second_change;
UPDATE students SET name = 'commit' WHERE id = 2;
ROLLBACK TO SAVEPOINT before_second_change;
COMMIT;
SELECT id, name FROM students WHERE id <= 2;

DROP INDEX idx_students_id;

CREATE TABLE scratch (id int, tag char);
INSERT INTO scratch (id, tag) VALUES (1, 'tmp');
ALTER TABLE scratch ADD extra int;
SELECT * FROM scratch;
ALTER TABLE scratch DROP COLUMN extra;
SHOW TABLES;
