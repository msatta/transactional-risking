Single errors will be returned in the following format:<br>
`{
    "code": "FORMAT_FIELD_NAME",
    "message": "The provided FieldName is invalid"
}`

Where possible, multiple errors will be returned with `INVALID_REQUEST` in the following format:<br>
`{
    "code": "INVALID_REQUEST",
    "message": "Invalid request",
    "errors": [
        {
            "code": "RULE_FIELD_NAME",
            "message": "The provided FieldName is not allowed"
        },
        {
            "code": "FORMAT_FIELD_NAME",
            "message": "The provided FieldName is invalid"
        }
    ]
}`