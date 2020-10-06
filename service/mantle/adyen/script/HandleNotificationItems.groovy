/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
import com.adyen.service.exception.ApiException;
import org.moqui.entity.EntityValue
import com.adyen.model.notification.NotificationRequestItem;
import com.adyen.util.HMACValidator;
import com.adyen.util.Util;

try {
    HMACValidator hmacValidator = new HMACValidator();
    List<NotificationRequestItem> notificationRequestItems = (List<NotificationRequestItem>) notificationItems;
    for ( Map<String,Object> notificationItem : notificationItems ) {
        Map<String, Object> notificationRequestItem = notificationItem.NotificationRequestItem;
        String eventCode = notificationRequestItem.eventCode;
        if("AUTHORISATION".equals(eventCode)) { acknowledgement = "[accepted]"; return}
        EntityValue payment = ec.entity.find("mantle.account.payment.Payment").condition('paymentId', notificationRequestItem.merchantReference).one()
        if (payment == null) { ec.message.addError("Payment ${notificationRequestItem.merchantReference} not found"); return }
        Map paymentGatewayInfo = ec.service.sync().name("mantle.adyen.AdyenServices.get#PaymentGatewayConfig").parameters([
                paymentGatewayConfigId:payment.paymentGatewayConfigId]).call()
        if (paymentGatewayInfo == null) { ec.message.addError("Could not find Adyen gateway configuration or error connecting"); return }
        List<String> signedDataList = new ArrayList<>(8);
        signedDataList.add(notificationRequestItem.pspReference);
        signedDataList.add(notificationRequestItem.originalReference);
        signedDataList.add(notificationRequestItem.merchantAccountCode);
        signedDataList.add(notificationRequestItem.merchantReference);
        Map<String, Object> amount = notificationRequestItem.amount;
        signedDataList.add(amount.value.toString());
        signedDataList.add(amount.currency);
        signedDataList.add(notificationRequestItem.eventCode);
        signedDataList.add(String.valueOf(notificationRequestItem.success));
        String dataToSign = Util.implode(":", signedDataList);
        String expectedSign = hmacValidator.calculateHMAC(dataToSign, paymentGatewayInfo.pgcInfoMap.hmacKey);
        Map<String, Object> additionalData = notificationRequestItem.additionalData;
        String merchantSign = additionalData.hmacSignature;
        if (expectedSign.equals(merchantSign)) {
            // Handle the notification
            String resultSuccess = (Boolean.parseBoolean(notificationRequestItem.success))? "Y":"N";
            String resultDeclined = (Boolean.parseBoolean(notificationRequestItem.success))? "N":"Y";

            if("CANCELLATION".equals(eventCode) && "Y".equals(resultSuccess)) {
                ec.service.sync().name("update#mantle.account.payment.Payment").parameters(["paymentId": payment.paymentId,
                        "statusId": "PmntPromised", "paymentAuthCode": null, "paymentRefNum": null]).call()
                paymentOperationEnumId = "PgoRelease";
            } else if("CAPTURE".equals(eventCode)) {
                paymentOperationEnumId = "PgoCapture"
                if(("Y".equals(resultSuccess))) {
                    ec.service.sync().name("update#mantle.account.payment.Payment").parameters(["paymentId": payment.paymentId,
                            "statusId": "PmntDelivered", "effectiveDate": ec.user.nowTimestamp]).call()
                } else {
                    ec.service.sync().name("update#mantle.account.payment.Payment").parameters(["paymentId": payment.paymentId,
                            "statusId": "PmntDeclined"]).call()
                }
            } else if("REFUND".equals(eventCode)) {
                paymentOperationEnumId = "PgoRefund";
                if("Y".equals(resultSuccess)) {
                    ec.service.sync().name("mantle.account.PaymentServices.create#RefundPayment").parameters([
                            paymentId:payment.paymentId, amount:amount.value, statusId:'PmntDelivered', paymentMethodId:payment.toPaymentMethodId,
                            toPaymentMethodId:payment.paymentMethodId, paymentInstrumentEnumId:payment.paymentInstrumentEnumId,
                            paymentGatewayConfigId:payment.paymentGatewayConfigId]).call()
                }
            }

            Map createPgrOut =  ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
                    paymentGatewayConfigId:payment.paymentGatewayConfigId, paymentOperationEnumId:paymentOperationEnumId, paymentId:payment.paymentId,
                    paymentMethodId:payment.paymentMethodId, amountUomId:payment.amountUomId, amount:amount.value,
                    referenceNum:notificationRequestItem.pspReference, reasonMessage:notificationRequestItem.reason,
                    transactionDate:ec.user.nowTimestamp, resultSuccess:resultSuccess, resultDeclined:resultDeclined, resultError:"N",
                    resultNsf:"N", resultBadExpire:"N", resultBadCardNumber:"N"]).call()

            // Process the notification/business logic based on the eventCode["AUTHORISATION", "CANCELLATION", "CAPTURE", "REFUND", ......]
            // For more visit https://docs.adyen.com/development-resources/webhooks/understand-notifications
        } else {
            // inValid NotificationRequest
            ec.message.addError("Invalid NotificationRequest:"+notificationItems);
            return
        }
    }
    acknowledgement = "[accepted]";

} catch (ApiException e) {
    ec.message.addError("Adyen API exception ${e}")
} catch (Exception ge) {
    ec.message.addError("Adyen exception: ${ge.toString()}")
}
