# Power app business rules. 

This file describes the business rules within the existing Deed Poll power app which need to be incorperated into the migration to a web app. 

## Business rules

| business rule name | description |
| --- | --- |
| Default Minor Deed Poll to Single | If Deed Poll Type is set to Minor when creating the record, default Marital Status to Single |
| Make fields required or non-required depending on Type and Migrated Record | Make fields required or non-required depending on Type (e.g. Adult) and Migrated Record (whether the record is new or has been migrated from the previous Deed Poll system). [View detailed rule](#make-fields-required-or-non-required-depending-on-type-and-migrated-record) |
| Set States based on Dates completed | When a Date field is completed, set the State accordingly [View detailed rule](#set-states-based-on-dates-completed)|
| Show and mandate Urgency Reason | If Urgency is Urgent, show and mandate the Urgency Reason [View detailed rule](#show-and-mandate-urgency-reason) |
| Show Online Application reference and mandate | Show Online Application reference and make it mandatory [View detailed rule](#show-online-application-referenc-and-mandate) |
| Show or hide Date Notice Sent to London Gazette | If Publish to London Gazette is No, clear and hide Date Notice Sent to London Gazette, otherwise show it. [View detailed rule](#show-or-hide-date-notice-sent-to-london-gazette) |
| Show, hide and mandate fields for Deed of Relinquishment | Show, hide and mandate fields for Type of Deed of Relinquishment. Note, some field requirements are controlled by the rule 'Make fields required or non-required depending on Type and Migrated Record' [View detailed rule](#show-hide-and-mandate-fields-for-deed-of-relinquishment)|
| Show, hide and mandate fields for Minors | Show, hide and mandate fields when Type starts with Minor. Note, some field requirements are controlled by the rule 'Make fields required or non-required depending on Type and Migrated Record' [View detailed rule](#show-hide-and-mandate-fields-for-minors) |


## Further business rule context

### Make fields required or non-required depending on Type and Migrated Record
IF
Migrated Record equals "Yes"
THEN
Set In Person? as Not Business Required
Set Marital Status as Not Business Required
Set Age of Minor Years as Not Business Required
Set Age of Minor Months as Not Business Required
Set Parents as Not Business Required
Set Urgency as Not Business Required
Set Nationality as Not Business Required
Set Section as Not Business Required
ELSE IF
Migrated Record equals "No" AND Type equals "Adult,Adult Transgender"
THEN
Set In Person? as Business Required
Set Marital Status as Business Required
Set Age of Minor Years as Not Business Required
Set Age of Minor Months as Not Business Required
Set Parents as Not Business Required
Set Urgency as Business Required
Set Nationality as Business Required
Set Section as Business Required
ELSE IF
Migrated Record equals "No" AND Type equals "Minor,Minor Transgender"
THEN
Set In Person? as Business Required
Set Marital Status as Business Required
Set Age of Minor Years as Business Required
Set Age of Minor Months as Business Required
Set Parents as Business Required
Set Urgency as Business Required
Set Nationality as Business Required
Set Section as Business Required
ELSE IF
Migrated Record equals "No" AND Type equals "Deed of Relinquishment"
THEN
Set In Person? as Business Required
Set Marital Status as Not Business Required
Set Age of Minor Years as Not Business Required
Set Age of Minor Months as Not Business Required
Set Parents as Not Business Required
Set Urgency as Business Required
Set Nationality as Not Business Required
Set Section as Not Business Required

### Set States based on Dates completed
IF
Date Returned contains data AND Date Enroled contains data
THEN
Show the error message "Date Enroled and Date Returned both contain a date. Please remove one depending on whether the Deed Poll is to be enroled or returned." against field State
ELSE IF
Date Returned contains data
THEN
Set State to "Returned"
ELSE IF
Date Enroled contains data
THEN
Set State to "Enroled"
ELSE IF
Signature Received Date contains data
THEN
Set State to "Awaiting Enrolment"
ELSE IF
Signature Requested Date contains data
THEN
Set State to "Awaiting Signature"
ELSE IF
Date Referred contains data AND Date Ready for Master contains data
THEN
Set State to "Referred to Master"
ELSE IF
Date Ready for Master contains data
THEN
Set State to "Ready for Master"
ELSE IF
Awaiting further docs/info contains data
THEN
Set State to "Awaiting Further Docs/Info"
ELSE
Set State to "Received"

### Show and mandate Urgency Reason
IF
Urgency equals "Urgent"
THEN
Show field Urgency Reason
Set Urgency Reason as Business Required
ELSE
Clear Urgency Reason
Hide field Urgency Reason
Set Urgency Reason as Not Business Required

### Show Online Application reference and mandate
IF
Post / Counter / Online equals "Online"
THEN
Set Online Application Reference as Business Required
Show field Online Application Reference
ELSE
Set Online Application Reference as Not Business Required
Hide field Online Application Reference

### Show or hide Date Notice Sent to London Gazette
IF
Publish to London Gazette? equals "No"
THEN
Clear Date Notice Sent To London Gazette
Hide field Date Notice Sent To London Gazette
ELSE
Show field Date Notice Sent To London Gazette

### Show, hide and mandate fields for Deed of Relinquishment
IF
Type equals "Deed of Relinquishment"
THEN
Set default value of Refer to Master? to "Yes"
Set Old Forename as Not Business Required
Clear Old Forename
Hide field Old Forename
Clear Old Middle Name
Hide field Old Middle Name
Clear Old Surname
Hide field Old Surname
Clear Marital Status
Hide field Marital Status
Clear Nationality
Hide field Nationality
Clear Section
Hide field Section
Clear Section Other
Hide field Section Other
Set Publish to London Gazette? to "No"
Hide field Date Notice Sent To London Gazette
ELSE
Set Old Forename as Business Required
Show field Old Forename
Show field Old Middle Name
Show field Old Surname
Show field Marital Status
Show field Nationality
Show field Section
Show field Section Other
Show field Date Notice Sent To London Gazette

### Show, hide and mandate fields for Minors
IF
Type equals "Minor,Minor Transgender"
THEN
Show field Age of Minor Years
Show field Age of Minor Months
Show field Minor Date of Birth
Show field Parents
ELSE
Clear Age of Minor Years
Clear Age of Minor Months
Clear Minor Date of Birth
Clear Parents
Hide field Age of Minor Years
Hide field Age of Minor Months
Hide field Minor Date of Birth
Hide field Parents