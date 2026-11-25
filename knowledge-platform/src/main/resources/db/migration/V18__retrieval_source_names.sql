-- Expose public source names through the same scoped read path as chunk content.
CREATE VIEW public.document_chunk_retrieval AS
SELECT c.id,
       c.document_id,
       c.knowledge_base_id,
       c.chunk_index,
       c.content,
       c.source_type,
       c.page_no,
       c.heading_path,
       c.search_vector,
       d.file_name AS source_name
FROM public.document_chunk c
JOIN public.document d
  ON d.id = c.document_id
 AND d.knowledge_base_id = c.knowledge_base_id;

GRANT SELECT ON public.document_chunk_retrieval TO rag_readonly;
