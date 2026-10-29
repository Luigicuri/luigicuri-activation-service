-- W3C traceparent of the request that created the activation (R9.2): the worker continues that trace
-- when it calls the provider, so the whole activation shows up as one distributed trace.
ALTER TABLE activation_request ADD COLUMN trace_parent VARCHAR(55);
