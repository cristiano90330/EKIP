-- Standard DOCX/XLSX/PPTX MIME values exceed the original varchar(50).
-- Keep the complete client media type, including parameters; parsing still uses file extensions.
ALTER TABLE document ALTER COLUMN file_type TYPE text;
