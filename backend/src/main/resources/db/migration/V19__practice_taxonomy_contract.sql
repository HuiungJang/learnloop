UPDATE problems
SET difficulty =
    CASE lower(difficulty)
        WHEN 'beginner' THEN 'easy'
        WHEN 'intermediate' THEN 'medium'
        WHEN 'advanced' THEN 'hard'
        ELSE lower(difficulty)
    END;

ALTER TABLE problems
    DROP CONSTRAINT IF EXISTS problems_difficulty_check;

ALTER TABLE problems
    ADD CONSTRAINT problems_difficulty_check CHECK (difficulty IN ('easy', 'medium', 'hard'));

ALTER TABLE problem_files
    DROP CONSTRAINT IF EXISTS problem_files_language_check;

ALTER TABLE problem_files
    ADD CONSTRAINT problem_files_language_check CHECK (
        language IN ('typescript', 'kotlin', 'java', 'swift', 'rust', 'json', 'markdown', 'text')
    );

ALTER TABLE submissions
    DROP CONSTRAINT IF EXISTS submissions_language_check;

ALTER TABLE submissions
    ADD CONSTRAINT submissions_language_check CHECK (
        language IS NULL OR language IN ('typescript', 'kotlin', 'java', 'swift', 'rust')
    );
