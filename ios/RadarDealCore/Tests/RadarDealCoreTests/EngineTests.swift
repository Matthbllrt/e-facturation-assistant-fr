import XCTest
@testable import RadarDealCore

/// Parser, query builder, deal engine, id cache and formatters — the same coverage the Android
/// suite holds, so a behaviour can never diverge silently between platforms.
final class VintedParserTests: XCTestCase {

    private let fullPayload = """
    {"items":[
      {"id":4821337,"title":"Nike Air Max 95 OG","brand_title":"Nike","size_title":"42",
       "status":"Très bon état","price":{"amount":"39.0","currency_code":"EUR"},
       "url":"https://www.vinted.fr/items/4821337-nike-air-max-95",
       "photo":{"url":"https://images.vinted.net/photo1.jpg"}},
      {"id":4821338,"title":"Air Max 95 Neon","brand_title":"Nike","size_title":"43",
       "status":"Bon état","price":{"amount":"72.50","currency_code":"EUR"},
       "url":"https://www.vinted.fr/items/4821338-air-max-95-neon",
       "photo":{"url":"https://images.vinted.net/photo2.jpg"}}
    ],"pagination":{"current_page":1,"total_pages":12}}
    """

    func testParsesTheStandardCataloguePayload() {
        let items = VintedParser.parse(fullPayload)
        XCTAssertEqual(items.count, 2)

        let first = items[0]
        XCTAssertEqual(first.itemID, "4821337")
        XCTAssertEqual(first.title, "Nike Air Max 95 OG")
        XCTAssertEqual(first.brand, "Nike")
        XCTAssertEqual(first.size, "42")
        XCTAssertEqual(first.condition, "Très bon état")
        XCTAssertEqual(first.price, 39.0)
        XCTAssertEqual(first.currency, "EUR")
        XCTAssertEqual(first.imageURL, "https://images.vinted.net/photo1.jpg")
    }

    func testReadsAPriceGivenAsNumberOrString() {
        let payload = """
        {"items":[{"id":1,"title":"a","price":42},
                  {"id":2,"title":"b","price":"37.50"},
                  {"id":3,"title":"c","price":"29,90"}]}
        """
        XCTAssertEqual(VintedParser.parse(payload).map(\.price), [42.0, 37.5, 29.9])
    }

    func testFallsBackToTotalItemPrice() {
        let payload = """
        {"items":[{"id":9,"title":"a","total_item_price":{"amount":"55.0","currency_code":"EUR"}}]}
        """
        XCTAssertEqual(VintedParser.parse(payload).first?.price, 55.0)
    }

    func testKeepsAListingMissingEverythingExceptItsID() {
        let item = try! XCTUnwrap(VintedParser.parse(#"{"items":[{"id":777,"title":"Sans détails"}]}"#).first)
        XCTAssertEqual(item.itemID, "777")
        XCTAssertNil(item.price)
        XCTAssertNil(item.brand)
        XCTAssertNil(item.imageURL)
        // A URL can always be reconstructed from the id, so the card stays actionable.
        XCTAssertEqual(item.itemURL, "https://www.vinted.fr/items/777")
    }

    func testDropsEntriesWithoutAnID() {
        let items = VintedParser.parse(#"{"items":[{"title":"orphan"},{"id":5,"title":"kept"}]}"#)
        XCTAssertEqual(items.map(\.itemID), ["5"])
    }

    func testResolvesRelativeItemURLs() {
        let payload = #"{"items":[{"id":5,"title":"a","url":"/items/5-truc"}]}"#
        XCTAssertEqual(VintedParser.parse(payload, host: "www.vinted.be").first?.itemURL,
                       "https://www.vinted.be/items/5-truc")
    }

    func testUsesAThumbnailWhenNoDirectPhotoURL() {
        let payload = """
        {"items":[{"id":5,"title":"a","photo":{"thumbnails":[
          {"url":"https://img/small.jpg"},{"url":"https://img/large.jpg"}]}}]}
        """
        XCTAssertNotNil(VintedParser.parse(payload).first?.imageURL)
    }

    func testReturnsEmptyForUnparseablePayloadsInsteadOfThrowing() {
        let payloads: [String?] = [
            nil, "", "   ", "not json at all", "{", "[]", "{}",
            #"{"items":[]}"#, #"{"items":"unexpectedly a string"}"#,
        ]
        for payload in payloads {
            XCTAssertTrue(VintedParser.parse(payload).isEmpty, "payload: \(payload ?? "nil")")
        }
    }

    func testFindsTheListingsArrayEvenWhenNested() {
        let payload = #"{"data":{"catalog":{"items":[{"id":8,"title":"nested","price":12}]}}}"#
        XCTAssertEqual(VintedParser.parse(payload).first?.itemID, "8")
    }

    func testDetectsAnAntiBotInterstitial() {
        let pages = [
            "<html><head><title>Just a moment...</title></head><body>cf-challenge</body></html>",
            "<html><body><div id=\"px-captcha\"></div></body></html>",
            "<!DOCTYPE html><html><body>Checking your browser before accessing</body></html>",
        ]
        for page in pages { XCTAssertTrue(VintedParser.looksLikeChallenge(page)) }
    }

    func testDoesNotMistakeAListingTitledCaptchaForAChallenge() {
        let payload = #"{"items":[{"id":1,"title":"T-shirt CAPTCHA vintage","price":10}]}"#
        XCTAssertFalse(VintedParser.looksLikeChallenge(payload))
        XCTAssertEqual(VintedParser.parse(payload).count, 1)
    }

    func testRecognisesHTML() {
        XCTAssertTrue(VintedParser.looksLikeHTML("<!DOCTYPE html><html>…"))
        XCTAssertFalse(VintedParser.looksLikeHTML(fullPayload))
        XCTAssertFalse(VintedParser.looksLikeHTML(nil))
    }

    func testRecoversTheCatalogueEmbeddedInAnHTMLPage() {
        let html = """
        <!DOCTYPE html><html><head><title>Vinted</title></head><body>
        <script>window.__DATA__ = {"catalog":{"items":[
          {"id":314,"title":"Trouvé dans le HTML","price":{"amount":"25.0"}}
        ]},"other":"value"};</script>
        </body></html>
        """
        let items = VintedParser.parse(html)
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items.first?.itemID, "314")
        XCTAssertEqual(items.first?.price, 25.0)
    }

    func testHandlesBracesInsideStrings() {
        let html = #"<html><body><script>var x = {"note":"a } brace \" inside","items":[{"id":99,"title":"ok","price":5}]};</script></body></html>"#
        XCTAssertEqual(VintedParser.parse(html).first?.itemID, "99")
    }
}

final class VintedQueryTests: XCTestCase {

    private func watch(keyword: String? = nil, brand: String? = nil,
                       maxPrice: Double? = nil, sourceURL: String? = nil) -> Watch {
        Watch(id: 1, name: "Test", keyword: keyword, brand: brand,
              maxPrice: maxPrice, sourceURL: sourceURL)
    }

    func testAcceptsANormalVintedSearchURL() {
        guard case let .valid(host, _) = VintedQuery.checkSearchURL(
            "https://www.vinted.fr/catalog?search_text=air+max"
        ) else { return XCTFail("expected valid") }
        XCTAssertEqual(host, "www.vinted.fr")
    }

    func testAcceptsOtherMarketplaces() {
        for url in ["https://www.vinted.be/catalog?search_text=x",
                    "https://vinted.de/catalog?search_text=x",
                    "https://www.vinted.co.uk/catalog?search_text=x"] {
            guard case .valid = VintedQuery.checkSearchURL(url) else {
                return XCTFail("expected valid: \(url)")
            }
        }
    }

    func testAddsAMissingScheme() {
        guard case let .valid(_, normalized) = VintedQuery.checkSearchURL(
            "www.vinted.fr/catalog?search_text=x"
        ) else { return XCTFail("expected valid") }
        XCTAssertTrue(normalized.hasPrefix("https://"))
    }

    func testRejectsNonVintedAndLookAlikeHosts() {
        for url in ["https://www.leboncoin.fr/recherche?text=x",
                    "https://vinted.fr.evil.example/catalog"] {
            guard case .invalid = VintedQuery.checkSearchURL(url) else {
                return XCTFail("expected invalid: \(url)")
            }
        }
    }

    func testRejectsAnItemURLWithAHelpfulMessage() {
        guard case let .invalid(reason) = VintedQuery.checkSearchURL(
            "https://www.vinted.fr/items/123456-nike-air-max"
        ) else { return XCTFail("expected invalid") }
        XCTAssertTrue(reason.contains("annonce"))
    }

    func testRejectsBlankAndMalformedInput() {
        for input: String? in [nil, "", "   ", "ht!tp:// not a url", "://"] {
            guard case .invalid = VintedQuery.checkSearchURL(input) else {
                return XCTFail("expected invalid: \(input ?? "nil")")
            }
        }
    }

    func testCollapsesBracketedArrayParameters() {
        let params = Dictionary(uniqueKeysWithValues: VintedQuery.forwardedParams(
            from: "https://www.vinted.fr/catalog?brand_ids[]=53&brand_ids[]=88&size_ids[]=207"
        ))
        XCTAssertEqual(params["brand_ids"], "53,88")
        XCTAssertEqual(params["size_ids"], "207")
    }

    func testRenamesCatalogToCatalogIDs() {
        let params = Dictionary(uniqueKeysWithValues: VintedQuery.forwardedParams(
            from: "https://www.vinted.fr/catalog?catalog[]=1231"
        ))
        XCTAssertEqual(params["catalog_ids"], "1231")
        XCTAssertNil(params["catalog"])
    }

    func testPreservesAdvancedFilters() {
        let params = Dictionary(uniqueKeysWithValues: VintedQuery.forwardedParams(
            from: "https://www.vinted.fr/catalog?search_text=air%20max&status_ids[]=2&color_ids[]=1&price_to=70&currency=EUR"
        ))
        XCTAssertEqual(params["search_text"], "air max")
        XCTAssertEqual(params["status_ids"], "2")
        XCTAssertEqual(params["color_ids"], "1")
        XCTAssertEqual(params["price_to"], "70")
    }

    func testAlwaysSortsByNewestFirst() {
        let params = Dictionary(uniqueKeysWithValues: VintedQuery.forwardedParams(
            from: "https://www.vinted.fr/catalog?search_text=x&order=price_low_to_high"
        ))
        XCTAssertEqual(params["order"], "newest_first")
    }

    func testDropsPagingParameters() {
        let params = Dictionary(uniqueKeysWithValues: VintedQuery.forwardedParams(
            from: "https://www.vinted.fr/catalog?search_text=x&page=7&per_page=10"
        ))
        XCTAssertNil(params["page"])
        XCTAssertNil(params["per_page"])
    }

    func testCombinesBrandAndKeyword() {
        let params = Dictionary(uniqueKeysWithValues: VintedQuery.criteriaParams(
            for: watch(keyword: "Air Max 95", brand: "Nike")
        ))
        XCTAssertEqual(params["search_text"], "Nike Air Max 95")
    }

    func testWritesAWholeMaxPriceWithoutADecimalPoint() {
        let params = Dictionary(uniqueKeysWithValues: VintedQuery.criteriaParams(
            for: watch(keyword: "x", maxPrice: 70)
        ))
        XCTAssertEqual(params["price_to"], "70")
    }

    func testBuildsAnAPIEndpointOnTheHostOfThePastedURL() {
        let endpoints = VintedQuery.endpoints(
            for: watch(sourceURL: "https://www.vinted.be/catalog?search_text=x")
        )
        XCTAssertEqual(endpoints.host, "www.vinted.be")
        XCTAssertTrue(endpoints.apiURL.hasPrefix("https://www.vinted.be/api/v2/catalog/items?"))
        XCTAssertEqual(endpoints.browseURL, "https://www.vinted.be/catalog?search_text=x")
    }

    func testTreatsAnInvalidSourceURLAsCriteria() {
        let endpoints = VintedQuery.endpoints(
            for: watch(keyword: "Air Max", sourceURL: "not a url at all")
        )
        XCTAssertEqual(endpoints.host, "www.vinted.fr")
        XCTAssertTrue(endpoints.apiURL.contains("search_text=Air+Max"))
    }

    func testAlwaysRequestsAPageSize() {
        let endpoints = VintedQuery.endpoints(for: watch(keyword: "x"))
        XCTAssertTrue(endpoints.apiURL.contains("per_page="))
        XCTAssertTrue(endpoints.apiURL.contains("page=1"))
    }
}

final class DealEngineTests: XCTestCase {

    private let tenPricesAroundSixtySeven: [Double] =
        [50, 55, 60, 65, 67, 68, 70, 75, 80, 90]

    func testMedianOfOddAndEvenSamples() {
        XCTAssertEqual(DealEngine.median([10, 30, 20]), 20)
        XCTAssertEqual(DealEngine.median([10, 20, 30, 40]), 25)
    }

    func testMedianIgnoresNonPositiveAndNonFiniteValues() {
        XCTAssertEqual(DealEngine.median([10, -5, 0, .nan, 30, 20]), 20)
    }

    func testMedianOfNothingIsNil() {
        XCTAssertNil(DealEngine.median([]))
        XCTAssertNil(DealEngine.median([-1, 0]))
    }

    func testRobustMedianDiscardsBundleOutliers() {
        let robust = try! XCTUnwrap(DealEngine.robustMedian([1, 60, 65, 70, 75, 80]))
        XCTAssertGreaterThanOrEqual(robust, 65)
    }

    func testStaysSilentBelowTheMinimumComparables() {
        let assessment = DealEngine.assess(price: 20, allPrices: [60, 70, 80])
        XCTAssertEqual(assessment.rating, .none)
        XCTAssertNil(assessment.discount)
        XCTAssertEqual(assessment.comparableCount, 3)
    }

    func testFlagsAnExcellentDeal() {
        let assessment = DealEngine.assess(price: 38, allPrices: tenPricesAroundSixtySeven)
        XCTAssertEqual(assessment.rating, .excellent)
        XCTAssertEqual(assessment.median, 67.5)
        XCTAssertEqual(try XCTUnwrap(assessment.discount), 0.437, accuracy: 0.01)
    }

    func testFlagsAGoodPriceAndStaysQuietNearTheMedian() {
        XCTAssertEqual(DealEngine.assess(price: 52, allPrices: tenPricesAroundSixtySeven).rating, .good)
        XCTAssertEqual(DealEngine.assess(price: 66, allPrices: tenPricesAroundSixtySeven).rating, .none)
        XCTAssertEqual(DealEngine.assess(price: 95, allPrices: tenPricesAroundSixtySeven).rating, .none)
    }

    func testHandlesAListingWithNoPrice() {
        XCTAssertEqual(DealEngine.assess(price: nil, allPrices: tenPricesAroundSixtySeven).rating, .none)
    }

    func testThresholdsAreExactlyAtTheDocumentedBoundaries() {
        let prices = Array(repeating: 100.0, count: 10)
        XCTAssertEqual(DealEngine.assess(price: 65, allPrices: prices).rating, .excellent)
        XCTAssertEqual(DealEngine.assess(price: 66, allPrices: prices).rating, .good)
        XCTAssertEqual(DealEngine.assess(price: 80, allPrices: prices).rating, .good)
        XCTAssertEqual(DealEngine.assess(price: 81, allPrices: prices).rating, .none)
    }
}

final class KnownIDCacheTests: XCTestCase {

    func testColdCacheTreatsEverythingAsUnknown() {
        let cache = KnownIDCache()
        XCTAssertFalse(cache.isWarm(1))
        XCTAssertEqual(cache.filterUnknown(1, ["a", "b"]), ["a", "b"])
    }

    func testWarmingStoresIDs() {
        let cache = KnownIDCache()
        cache.warm(1, ids: ["a", "b", "c"])
        XCTAssertTrue(cache.isWarm(1))
        XCTAssertEqual(cache.size(1), 3)
        XCTAssertTrue(cache.isKnown(1, "b"))
        XCTAssertFalse(cache.isKnown(1, "z"))
    }

    func testFilterUnknownPreservesOrderAndReturnsOnlyNewIDs() {
        let cache = KnownIDCache()
        cache.warm(1, ids: ["a", "c"])
        XCTAssertEqual(cache.filterUnknown(1, ["a", "b", "c", "d"]), ["b", "d"])
        XCTAssertTrue(cache.filterUnknown(1, ["a", "c"]).isEmpty)
    }

    func testWatchesDoNotShareIDs() {
        let cache = KnownIDCache()
        cache.warm(1, ids: ["shared"])
        cache.warm(2, ids: [])
        XCTAssertTrue(cache.isKnown(1, "shared"))
        XCTAssertFalse(cache.isKnown(2, "shared"))
    }

    func testClaimSucceedsOnceAndOnlyOnce() {
        let cache = KnownIDCache()
        cache.warm(1, ids: [])
        XCTAssertTrue(cache.claim(1, "new"))
        XCTAssertFalse(cache.claim(1, "new"))
    }

    /// The race that matters in production: two concurrent scans seeing the same listing.
    /// Exactly one must be allowed to notify.
    func testConcurrentClaimsElectASingleWinner() {
        let cache = KnownIDCache()
        cache.warm(1, ids: [])
        let winners = NSCounter()

        DispatchQueue.concurrentPerform(iterations: 64) { _ in
            if cache.claim(1, "contested") { winners.increment() }
        }
        XCTAssertEqual(winners.value, 1)
    }

    func testConcurrentClaimsOfDistinctIDsAllSucceed() {
        let cache = KnownIDCache()
        cache.warm(1, ids: [])
        let winners = NSCounter()

        DispatchQueue.concurrentPerform(iterations: 500) { i in
            if cache.claim(1, "id-\(i)") { winners.increment() }
        }
        XCTAssertEqual(winners.value, 500)
        XCTAssertEqual(cache.size(1), 500)
    }

    func testForgettingDropsIDsAndTheWarmFlag() {
        let cache = KnownIDCache()
        cache.warm(1, ids: ["a"])
        cache.forget(1)
        XCTAssertFalse(cache.isWarm(1))
        XCTAssertFalse(cache.isKnown(1, "a"))
    }
}

/// Minimal thread-safe counter; XCTest on Linux has no built-in equivalent.
final class NSCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var value: Int { lock.lock(); defer { lock.unlock() }; return count }
    func increment() { lock.lock(); count += 1; lock.unlock() }
}

final class FormattersTests: XCTestCase {

    func testWholeAndFractionalPrices() {
        XCTAssertEqual(Formatters.price(42), "42 €")
        XCTAssertEqual(Formatters.price(39.5), "39,50 €")
    }

    func testAnUnknownPriceShowsALabelNeverNil() {
        XCTAssertEqual(Formatters.price(nil), "Prix inconnu")
        XCTAssertEqual(Formatters.price(.nan), "Prix inconnu")
        XCTAssertEqual(Formatters.price(.infinity), "Prix inconnu")
    }

    func testOtherMarketplaceCurrencies() {
        XCTAssertEqual(Formatters.price(20, currency: "GBP"), "20 £")
        XCTAssertEqual(Formatters.price(20, currency: "PLN"), "20 zł")
        XCTAssertEqual(Formatters.price(20, currency: nil), "20 €")
    }

    func testRelativeTimeReadsNaturally() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertEqual(Formatters.relativeTime(now.addingTimeInterval(-5), now: now), "À l'instant")
        XCTAssertEqual(Formatters.relativeTime(now.addingTimeInterval(-18), now: now), "Il y a 18 sec")
        XCTAssertEqual(Formatters.relativeTime(now.addingTimeInterval(-240), now: now), "Il y a 4 min")
        XCTAssertEqual(Formatters.relativeTime(now.addingTimeInterval(-3 * 3600), now: now), "Il y a 3 h")
        XCTAssertEqual(Formatters.relativeTime(now.addingTimeInterval(-2 * 86400), now: now), "Il y a 2 j")
    }

    func testANeverScannedWatchSaysSo() {
        XCTAssertEqual(Formatters.relativeTime(nil), "Jamais")
    }

    func testAClockThatMovedBackwardsDoesNotProduceANegativeDuration() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertEqual(Formatters.relativeTime(now.addingTimeInterval(60), now: now), "À l'instant")
    }

    func testDurations() {
        XCTAssertEqual(Formatters.duration(seconds: 3), "3 sec")
        XCTAssertEqual(Formatters.duration(seconds: 15), "15 sec")
        XCTAssertEqual(Formatters.duration(seconds: 60), "1 min")
        XCTAssertEqual(Formatters.duration(seconds: 3600), "1 h")
    }

    func testPercentages() {
        XCTAssertEqual(Formatters.percent(0.324), "32 %")
        XCTAssertEqual(Formatters.percent(nil), "—")
    }
}

final class ScanFrequencyTests: XCTestCase {

    func testTheLadderIsOrderedFastestToSlowest() {
        XCTAssertEqual(ScanFrequency.allCases.map(\.seconds), [3, 5, 15, 30])
    }

    func testFromSecondsNeverResolvesToUltra() {
        // Ultra is granted through its own path, never inferred from an interval.
        XCTAssertNotEqual(ScanFrequency.from(seconds: 1), .ultra)
        XCTAssertEqual(ScanFrequency.from(seconds: 3), .fast)
        XCTAssertEqual(ScanFrequency.from(seconds: 15), .standard)
        XCTAssertEqual(ScanFrequency.from(seconds: 30), .eco)
    }

    func testAnUltraWatchReportsTheUltraInterval() {
        var watch = Watch(name: "A", keyword: "x")
        watch.isUltra = true
        XCTAssertEqual(watch.frequency, .ultra)
        XCTAssertEqual(watch.baseInterval, 3)
    }

    func testStatusesThatHaltScanning() {
        XCTAssertTrue(ScanStatus.pausedVerification.haltsScanning)
        XCTAssertFalse(ScanStatus.rateLimited.haltsScanning)
        XCTAssertTrue(ScanStatus.rateLimited.isProblem)
        XCTAssertFalse(ScanStatus.ok.isProblem)
    }
}
