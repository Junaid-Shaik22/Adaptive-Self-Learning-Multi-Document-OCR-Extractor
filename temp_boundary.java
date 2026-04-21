    /**
     * Find boundary index in text
     */
    private int findBoundaryIndex(String text) {
        // Stop at parentheses or brackets first (employee IDs, etc.)
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '<') {
                return i;
            }
        }

        // Stop at next keyword
        for (String keyword : BOUNDARY_KEYWORDS) {
            int index = text.toLowerCase().indexOf(keyword.toLowerCase());
            if (index >= 0) {
                return index;
            }
        }

        // Stop at punctuation
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '/' || c == '\\') {
                return i;
            }
        }

        // Stop at first number (as last resort)
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return i;
            }
        }

        return -1; // No boundary found
    }
